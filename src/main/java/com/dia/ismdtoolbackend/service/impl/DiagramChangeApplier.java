package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramPendingEditEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.DiagramOp;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptEditModel;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.service.ConceptService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDFS;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies one staged overlay to the ontology in its OWN transaction ({@code REQUIRES_NEW}), so a failing
 * change rolls back only itself and cannot poison sibling changes in the same Převzít — the per-change
 * partial-ok guarantee. The overlay-clear commits with this change's transaction; a thrown exception rolls
 * back both the RDF edit and the clear, leaving the overlay staged. Callers run OUTSIDE a transaction and
 * translate a thrown exception into a {@code failed} report. See {@code docs/DIAGRAM_LAYER.md}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagramChangeApplier {

    private final ConceptService conceptService;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final DiagramPendingEditRepository pendingEditRepository;
    private final JenaTDB2Repository jenaTDB2Repository;

    /** The classified op plus the outcome, so the caller can report it without re-deriving the op. */
    public record Outcome(DiagramOp op, Kind kind) {
        public enum Kind { MATERIALIZED, SKIPPED_STALE }
    }

    /**
     * Apply one concept's staged edit in a fresh transaction and clear it on success. Re-loads the staged
     * row managed in this transaction (the caller's instance is from another persistence context). Throws
     * on any failure — the caller (non-transactional) records it as {@code failed}.
     *
     * <p>Addressed by {@code (ontology, conceptIri)} — a staged edit need not have a canvas node.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome applyChange(Long ontologyId, String conceptIri) {
        DiagramPendingEditEntity staged = pendingEditRepository
                .findByOntologyMetadataIdAndConceptIri(ontologyId, conceptIri)
                .orElse(null);
        if (staged == null || staged.getPendingEdit() == null) {
            // Raced away since the caller snapshotted — nothing to do.
            return new Outcome(null, Outcome.Kind.MATERIALIZED);
        }
        DiagramPendingEdit overlay = staged.getPendingEdit();
        DiagramOp op = classify(overlay);

        ConceptMetadataEntity concept =
                conceptMetadataRepository.findByConceptIri(conceptIri).orElse(null);
        if (concept == null) {
            return new Outcome(op, Outcome.Kind.SKIPPED_STALE);
        }
        String ontologyGraphName = staged.getOntologyMetadata().getGraphName();
        requireSameGraph(ontologyGraphName, concept.getGraphName(), concept.getConceptIri());

        if (isStaleBase(concept, overlay)) {
            throw new StaleBaseException("Pojem byl mezitím upraven; načtěte diagram znovu.");
        }

        if (op == DiagramOp.CONVERT_TO_HIERARCHY) {
            applyConvertToHierarchy(overlay, concept, ontologyGraphName);
        } else {
            conceptService.editConcept(concept.getId(), buildEdit(overlay, concept.getConceptType()));
        }
        // Applied to RDF; the staged row has served its purpose.
        pendingEditRepository.delete(staged);
        return new Outcome(op, Outcome.Kind.MATERIALIZED);
    }

    /**
     * Op 6: guard against a cascading delete, add the broader link on the target class, then delete the
     * VZTAH. All-or-nothing — the delete is gated on the broader-edit succeeding, and both run in this
     * change's transaction so a delete failure rolls back the broader-edit too.
     */
    private void applyConvertToHierarchy(DiagramPendingEdit overlay, ConceptMetadataEntity vztah,
                                         String diagramGraphName) {
        DiagramPendingEdit.ConvertToHierarchy marker = overlay.getConvertToHierarchy();
        List<String> related =
                jenaTDB2Repository.findRelatedConceptUris(vztah.getConceptIri(), vztah.getGraphName());
        if (related != null && !related.isEmpty()) {
            throw new CascadeConflictException(
                    "Vztah nelze převést — jiný pojem na něj odkazuje (smazání by kaskádovalo).");
        }

        ConceptMetadataEntity targetClass = conceptMetadataRepository.findByConceptIri(marker.getAddBroaderOn())
                .orElseThrow(() -> new ConceptValidationException(
                        "Cílová třída " + marker.getAddBroaderOn() + " nebyla nalezena."));
        requireSameGraph(diagramGraphName, targetClass.getGraphName(), targetClass.getConceptIri());
        requireSameGraphIri(diagramGraphName, marker.getBroader());

        ClassConceptEditModel addBroader = new ClassConceptEditModel();
        addBroader.setConceptType(ConceptType.TRIDA.getValue());
        addBroader.setBroaderConcept(mergedBroaderFor(targetClass, marker.getBroader()));
        conceptService.editConcept(targetClass.getId(), addBroader);

        conceptService.deleteConcept(vztah.getId());
    }

    /**
     * Op 6 <em>adds</em> a super-class; the edit model's {@code broaderConcept} is a full replace, so the
     * class's existing {@code rdfs:subClassOf} links must be read and carried through or the edit silently
     * drops them. Order is stable (existing first, new appended) and an already-present broader is a no-op.
     */
    private List<String> mergedBroaderFor(ConceptMetadataEntity targetClass, String newBroader) {
        Model graph = jenaTDB2Repository.fetchGraph(targetClass.getGraphName());
        Resource classRes = graph.getResource(targetClass.getConceptIri());

        List<String> merged = new ArrayList<>();
        StmtIterator it = classRes.listProperties(RDFS.subClassOf);
        while (it.hasNext()) {
            RDFNode object = it.next().getObject();
            if (object.isURIResource()) {
                String existing = object.asResource().getURI();
                if (!merged.contains(existing)) {
                    merged.add(existing);
                }
            }
        }
        if (newBroader != null && !merged.contains(newBroader)) {
            merged.add(newBroader);
        }
        return merged;
    }

    // ---- graph scoping --------------------------------------------------------------------------

    /**
     * The graph the diagram is allowed to write. Read through the node's diagram → ontology, so it is the
     * authorized slug's graph — the same ontology {@code belongsToUserBySlug} checked at the controller.
     */
    /**
     * A concept outside the diagram's own graph is a rejected request, not a stale reference: the diagram
     * endpoints authorize the ontology slug, so writing any other ontology's concept would escape that check.
     */
    private void requireSameGraph(String diagramGraphName, String conceptGraphName, String conceptIri) {
        if (!Objects.equals(diagramGraphName, conceptGraphName)) {
            log.warn("Rejected diagram write to foreign concept {} (graph {}) from a diagram on graph {}",
                    conceptIri, conceptGraphName, diagramGraphName);
            throw new ForeignConceptException(
                    "Pojem " + conceptIri + " nepatří do slovníku tohoto diagramu.");
        }
    }

    /**
     * Same check for a raw overlay IRI that need not have a PG row. An unresolvable IRI is not rejected here
     * — it becomes an ordinary rdfs:subClassOf object and cannot reach another ontology's concept; only a
     * row in a different graph is a cross-tenant reach.
     */
    private void requireSameGraphIri(String diagramGraphName, String conceptIri) {
        if (conceptIri == null) {
            return;
        }
        conceptMetadataRepository.findByConceptIri(conceptIri)
                .map(ConceptMetadataEntity::getGraphName).ifPresent(graphName -> requireSameGraph(diagramGraphName, graphName, conceptIri));
    }

    // ---- op classification ----------------------------------------------------------------------

    /**
     * Infer the op from the overlay's fields, disambiguated by the referencing concept's type. Op 4 vs 5
     * (change-parent vs set-domain) are indistinguishable from the overlay alone; both carry only a domain
     * on a VLASTNOST — reported as {@code CHANGE_PROPERTY_PARENT} (a set-on-empty is a parent change from
     * "none").
     */
    DiagramOp classify(DiagramPendingEdit overlay) {
        if (overlay.getConvertToHierarchy() != null) {
            return DiagramOp.CONVERT_TO_HIERARCHY;
        }
        if (overlay.getDomain() != null && overlay.getRange() != null) {
            return DiagramOp.SWAP_DIRECTION;
        }
        if (overlay.getBroaderConcept() != null

                || overlay.getExactMatch() != null) {
            return DiagramOp.CHANGE_HIERARCHY_TYPE;
        }
        return DiagramOp.CHANGE_PROPERTY_PARENT;
    }

    // ---- field-scoped edit model ----------------------------------------------------------------

    /** Build an edit carrying only the overlay's changed predicates for this concept type; rest stay null. */
    private ConceptEditModel buildEdit(DiagramPendingEdit overlay, ConceptType type) {
        return switch (type) {
            case TRIDA -> {
                ClassConceptEditModel m = new ClassConceptEditModel();
                m.setConceptType(ConceptType.TRIDA.getValue());
                m.setBroaderConcept(overlay.getBroaderConcept());
                m.setExactMatch(overlay.getExactMatch());
                yield m;
            }
            case VLASTNOST -> {
                PropertyConceptEditModel m = new PropertyConceptEditModel();
                m.setConceptType(ConceptType.VLASTNOST.getValue());
                m.setDomain(overlay.getDomain());
                m.setExactMatch(overlay.getExactMatch());
                yield m;
            }
            case VZTAH -> {
                RelationshipConceptEditModel m = new RelationshipConceptEditModel();
                m.setConceptType(ConceptType.VZTAH.getValue());
                m.setDomain(overlay.getDomain());
                m.setRange(overlay.getRange());
                m.setExactMatch(overlay.getExactMatch());
                yield m;
            }
            // A roleless concept carries none of the structural predicates an overlay stages; in practice
            // every diagram node has a specific role, so an overlay here is a programming error, not a no-op.
            case KONCEPT -> throw new ConceptValidationException(
                    "Pojem bez konkrétní role (KONCEPT) nelze převzít z diagramu.");
        };
    }

    private boolean isStaleBase(ConceptMetadataEntity concept, DiagramPendingEdit overlay) {
        LocalDateTime staged = overlay.getBaseUpdatedAt();
        if (staged == null) {
            return false;
        }
        return !Objects.equals(staged, concept.getUpdatedAt());
    }

    /** The referenced concept moved under the overlay since it was staged (409 STALE_BASE). */
    public static class StaleBaseException extends RuntimeException {
        public StaleBaseException(String message) {
            super(message);
        }
    }

    /** A concept IRI in the request does not belong to the diagram's own ontology graph (400). */
    public static class ForeignConceptException extends RuntimeException {
        public ForeignConceptException(String message) {
            super(message);
        }
    }

    /** Op 6 blocked because deleting the VZTAH would cascade to concepts pointing at it (409). */
    public static class CascadeConflictException extends RuntimeException {
        public CascadeConflictException(String message) {
            super(message);
        }
    }
}
