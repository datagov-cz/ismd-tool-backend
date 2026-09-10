package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEdgeEntity;
import com.dia.ismdtoolbackend.entity.DiagramPendingEditEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.DiagramEdgeKind;
import com.dia.ismdtoolbackend.enums.DiagramOp;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.exception.DiagramCascadeConflictException;
import com.dia.ismdtoolbackend.exception.DiagramForeignConceptException;
import com.dia.ismdtoolbackend.exception.DiagramStaleBaseException;
import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptEditModel;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramEdgeRepository;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.service.ConceptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies one staged overlay to the ontology in its own {@code REQUIRES_NEW} transaction, so a failing
 * change rolls back only itself and leaves its overlay staged. Callers run outside a transaction and turn
 * the exception into a {@code failed} report. See {@code docs/DIAGRAM_LAYER.md}.
 *
 * <p>That transaction covers PG. Whether the RDF write joins it depends on {@code outbox.enabled}: on the
 * outbox path (the default) the triples are enqueued in this same transaction and a rollback discards them
 * with the overlay clear, so the two stay consistent. On the direct path the TDB2 write has already
 * committed when PG rolls back, leaving RDF changed and the overlay still staged — the next materialize
 * re-applies it, which is why every op here must be idempotent. This is the repo-wide dual-write
 * limitation, not a diagram-specific one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagramChangeApplier {

    private final ConceptService conceptService;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final DiagramPendingEditRepository pendingEditRepository;
    private final DiagramEdgeRepository diagramEdgeRepository;
    private final JenaTDB2Repository jenaTDB2Repository;

    /** The classified op and the outcome, so the caller reports it without re-deriving the op. */
    public record Outcome(DiagramOp op, Kind kind) {

        /**
         * {@code NOTHING_STAGED} is not the outcome of applying anything: the staged row was gone before
         * this transaction read it, so no RDF was written and there is nothing to report. Distinct from
         * {@code MATERIALIZED}, which would otherwise claim a change that never happened.
         */
        public enum Kind { MATERIALIZED, SKIPPED_STALE, NOTHING_STAGED }
    }

    /**
     * Applies one concept's staged edit in a fresh transaction and clears it on success, throwing on any
     * failure for the caller to record as {@code failed}. Addressed by {@code (diagram, conceptIri)}, so a
     * staged edit need not have a node.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome applyChange(Long diagramId, String conceptIri) {
        DiagramPendingEditEntity staged = pendingEditRepository
                .findByDiagramIdAndConceptIri(diagramId, conceptIri)
                .orElse(null);
        if (staged == null || staged.getPendingEdit() == null) {
            // Raced away between the work-list read and this transaction — a retried Převzít, a concurrent
            // discard, or op 6 deleting the concept. Nothing was applied, so it is not a materialization.
            return new Outcome(null, Outcome.Kind.NOTHING_STAGED);
        }
        DiagramPendingEdit overlay = staged.getPendingEdit();
        DiagramOp op = classify(overlay);

        ConceptMetadataEntity concept =
                conceptMetadataRepository.findByConceptIri(conceptIri).orElse(null);
        if (concept == null) {
            return new Outcome(op, Outcome.Kind.SKIPPED_STALE);
        }
        String ontologyGraphName = staged.getOntologyMetadata().getGraphName();
        requireGraphMatches(ontologyGraphName, concept.getGraphName(), concept.getConceptIri());

        if (isStaleBase(concept, overlay)) {
            throw new DiagramStaleBaseException("Pojem byl mezitím upraven; načtěte diagram znovu.");
        }

        // Captured before the edit: a triple edge's id embeds its endpoints, so once RDF moves them the
        // old ids are unrecoverable and a membership row would strand on a key nothing projects.
        List<EdgeRekey> rekeys = plannedEdgeRekeys(diagramId, concept, overlay);

        if (op == DiagramOp.CONVERT_TO_HIERARCHY) {
            applyConvertToHierarchy(overlay, concept, ontologyGraphName);
        } else {
            // Re-asserted here: a row staged earlier could name a concept that has since moved graphs.
            requireResolvedIriIsOwnGraph(ontologyGraphName, overlay.getDomain());
            conceptService.editConcept(concept.getId(), buildEdit(overlay, concept.getConceptType()));
        }
        applyEdgeRekeys(rekeys);
        // Applied to RDF, so the staged row has served its purpose.
        pendingEditRepository.delete(staged);
        return new Outcome(op, Outcome.Kind.MATERIALIZED);
    }

    /** A membership row moving from the id it was placed under to the one the edit projects. */
    private record EdgeRekey(DiagramEdgeEntity row, String newKey) {
    }

    /**
     * Plans the membership moves this edit forces. A {@code SUBCLASS_OF}/{@code EXACT_MATCH} edge is keyed
     * {@code edge|KIND|source|target}, so repointing it changes the edge's identity — without the move the
     * row keeps the old key, stops matching the projection and leaves the canvas with its waypoints. A
     * VZTAH needs nothing here, its key being its own concept IRI.
     *
     * <p>Targets are matched by position: the n-th staged target replaces the n-th live one. A target the
     * overlay keeps is left alone, and an added one has no row to move.
     */
    private List<EdgeRekey> plannedEdgeRekeys(Long diagramId, ConceptMetadataEntity concept,
                                              DiagramPendingEdit overlay) {
        List<EdgeRekey> rekeys = new ArrayList<>();
        if (overlay.getBroaderConcept() == null && overlay.getExactMatch() == null) {
            return rekeys;
        }
        List<DiagramEdgeEntity> rows = diagramEdgeRepository.findByDiagramId(diagramId);
        if (rows.isEmpty()) {
            return rekeys;
        }
        String source = concept.getConceptIri();
        Model graph = jenaTDB2Repository.fetchGraph(concept.getGraphName());
        Resource conceptRes = graph.getResource(source);

        collectRekeys(rekeys, rows, source, uriObjects(conceptRes, RDFS.subClassOf),
                overlay.getBroaderConcept(), DiagramEdgeKind.SUBCLASS_OF);
        collectRekeys(rekeys, rows, source, uriObjects(conceptRes, SKOS.exactMatch),
                overlay.getExactMatch(), DiagramEdgeKind.EXACT_MATCH);
        return rekeys;
    }

    /** The URI objects of one predicate, in graph order; the live targets an edge id is built from. */
    private List<String> uriObjects(Resource subject, Property predicate) {
        List<String> uris = new ArrayList<>();
        StmtIterator it = subject.listProperties(predicate);
        while (it.hasNext()) {
            RDFNode object = it.next().getObject();
            if (object.isURIResource() && !uris.contains(object.asResource().getURI())) {
                uris.add(object.asResource().getURI());
            }
        }
        return uris;
    }

    private void collectRekeys(List<EdgeRekey> rekeys, List<DiagramEdgeEntity> rows, String source,
                               List<String> liveTargets, List<String> stagedTargets,
                               DiagramEdgeKind kind) {
        if (stagedTargets == null || liveTargets == null) {
            return;
        }
        for (int i = 0; i < liveTargets.size() && i < stagedTargets.size(); i++) {
            String oldTarget = liveTargets.get(i);
            String newTarget = stagedTargets.get(i);
            if (oldTarget == null || newTarget == null || oldTarget.equals(newTarget)) {
                continue;
            }
            String oldKey = EdgeProjector.projectedEdgeId(kind, source, oldTarget);
            String newKey = EdgeProjector.projectedEdgeId(kind, source, newTarget);
            rows.stream()
                    .filter(r -> oldKey.equals(r.getEdgeKey()))
                    .findFirst()
                    .ifPresent(row -> rekeys.add(new EdgeRekey(row, newKey)));
        }
    }

    /**
     * Moves each planned row onto its new key. A row already on the destination key means both links were
     * placed, so the moved row would collide on {@code (diagram_id, edge_key)} and is dropped instead.
     */
    private void applyEdgeRekeys(List<EdgeRekey> rekeys) {
        for (EdgeRekey rekey : rekeys) {
            boolean taken = diagramEdgeRepository.findByDiagramId(rekey.row().getDiagram().getId())
                    .stream()
                    .anyMatch(r -> !r.getId().equals(rekey.row().getId())
                            && rekey.newKey().equals(r.getEdgeKey()));
            if (taken) {
                diagramEdgeRepository.delete(rekey.row());
                continue;
            }
            rekey.row().setEdgeKey(rekey.newKey());
            diagramEdgeRepository.save(rekey.row());
        }
    }

    /**
     * Op 6: guards against a cascading delete, adds the broader link on the target class, then deletes the
     * VZTAH. All-or-nothing — both run in this change's transaction, so a delete failure rolls back the
     * broader-edit too.
     */
    private void applyConvertToHierarchy(DiagramPendingEdit overlay, ConceptMetadataEntity vztah,
                                         String diagramGraphName) {
        DiagramPendingEdit.ConvertToHierarchy marker = overlay.getConvertToHierarchy();
        List<String> related =
                jenaTDB2Repository.findRelatedConceptUris(vztah.getConceptIri(), vztah.getGraphName());
        if (related != null && !related.isEmpty()) {
            throw new DiagramCascadeConflictException(
                    "Vztah nelze převést — jiný pojem na něj odkazuje (smazání by kaskádovalo).");
        }

        ConceptMetadataEntity targetClass = conceptMetadataRepository.findByConceptIri(marker.getAddBroaderOn())
                .orElseThrow(() -> new ConceptValidationException(
                        "Cílová třída " + marker.getAddBroaderOn() + " nebyla nalezena."));
        // addBroaderOn is EDITED and must be ours; broader is only referenced, so it may be foreign.
        requireGraphMatches(diagramGraphName, targetClass.getGraphName(), targetClass.getConceptIri());

        ClassConceptEditModel addBroader = new ClassConceptEditModel();
        addBroader.setConceptType(ConceptType.TRIDA.getValue());
        addBroader.setBroaderConcept(mergedBroaderFor(targetClass, marker.getBroader()));
        conceptService.editConcept(targetClass.getId(), addBroader);

        conceptService.deleteConcept(vztah.getId());
    }

    /**
     * Op 6 adds a super-class, but the edit model's {@code broaderConcept} is a full replace, so existing
     * {@code rdfs:subClassOf} links are read and carried through. Existing first, new appended.
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
     * A concept outside the diagram's own graph is a rejected request, not a stale reference: the endpoints
     * authorize the ontology slug, so writing another ontology's concept would escape that check.
     *
     * <p>Takes an already-resolved graph name. To check a bare IRI instead, use
     * {@link #requireResolvedIriIsOwnGraph} — the two differ in whether an unresolvable IRI is tolerated,
     * which is why they are not one method.
     */
    private void requireGraphMatches(String diagramGraphName, String conceptGraphName, String conceptIri) {
        if (!Objects.equals(diagramGraphName, conceptGraphName)) {
            log.warn("Rejected diagram write to foreign concept {} (graph {}) from a diagram on graph {}",
                    conceptIri, conceptGraphName, diagramGraphName);
            throw new DiagramForeignConceptException(
                    "Pojem " + conceptIri + " nepatří do slovníku tohoto diagramu.");
        }
    }

    /**
     * The same check for a raw overlay IRI that need not have a PG row. An IRI that resolves to nothing is
     * tolerated <em>only</em> when it is foreign, since it then becomes an ordinary triple object — that
     * tolerance is the whole difference from {@link #requireGraphMatches}, which is given a graph and cannot
     * express it.
     *
     * <p>An unresolvable IRI carrying our own graph's scheme is a different thing entirely: it was ours and
     * the concept has since been deleted. Nothing downstream would catch it — {@code ConceptInputValidator}
     * has no {@code domain}/{@code range} rule, and {@code validateConceptInGraph} asserts only the subject
     * — so the edit would write a dangling {@code rdfs:domain} and the canvas would draw the property on a
     * class that no longer exists. Staled instead, which tells the user to re-point and restage.
     *
     * <p>Applied to {@code domain} only — a {@code range} or hierarchy target is referenced rather than
     * written, so a foreign one is legitimate.
     */
    private void requireResolvedIriIsOwnGraph(String diagramGraphName, String conceptIri) {
        if (conceptIri == null) {
            return;
        }
        ConceptMetadataEntity target = conceptMetadataRepository.findByConceptIri(conceptIri).orElse(null);
        if (target == null) {
            requireForeignIri(diagramGraphName, conceptIri);
            return;
        }
        requireGraphMatches(diagramGraphName, target.getGraphName(), conceptIri);
    }

    /**
     * Guards the unresolvable case: an IRI prefixed by the diagram's own graph name was owned by this
     * ontology, so its missing PG row means deletion, not foreignness. Scheme-prefix is the same
     * owned-vs-external test {@code NkdLinkDetector} applies.
     */
    private void requireForeignIri(String diagramGraphName, String conceptIri) {
        if (diagramGraphName != null && conceptIri.startsWith(diagramGraphName)) {
            log.warn("Staged edit references deleted own-graph concept {} (graph {})",
                    conceptIri, diagramGraphName);
            throw new DiagramStaleBaseException(
                    "Pojem " + conceptIri + " byl mezitím smazán; upravte vazbu a uložte diagram znovu.");
        }
    }

    // ---- op classification ----------------------------------------------------------------------

    /**
     * Infers the op from the overlay's fields alone — the concept's type is deliberately not consulted,
     * since the work-list classifies staged rows in one read and reaching the type would cost a query per
     * row (see {@code DiagramMaterializeService.orderedWorkList}).
     *
     * <p>That makes the last branch a fallthrough bucket rather than a precise label. Op 4 and 5
     * (change-parent, set-domain) are genuinely indistinguishable here — both carry only a domain on a
     * VLASTNOST — but a <em>domain-only edit on a VZTAH</em> also lands there and reports as
     * {@code CHANGE_PROPERTY_PARENT}, which names the wrong shape.
     *
     * <p><b>Label only.</b> The op is reporting metadata; the RDF written is driven by
     * {@code buildEdit(overlay, conceptType)}, which does see the type, so nothing is misapplied. Fixing the
     * label properly needs a new {@code DiagramOp} constant plus a concept-type lookup in the ordering path
     * — a real trade against that single-read design, not a rename.
     */
    DiagramOp classify(DiagramPendingEdit overlay) {
        if (overlay.getConvertToHierarchy() != null) {
            return DiagramOp.CONVERT_TO_HIERARCHY;
        }
        if (overlay.getDomain() != null && overlay.getRange() != null) {
            return DiagramOp.SWAP_DIRECTION;
        }
        if (overlay.getBroaderConcept() != null || overlay.getExactMatch() != null) {
            return DiagramOp.CHANGE_HIERARCHY_TYPE;
        }
        return DiagramOp.CHANGE_PROPERTY_PARENT;
    }

    // ---- field-scoped edit model ----------------------------------------------------------------

    /** Builds an edit carrying the overlay's predicates for this concept type; the rest stay null. */
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
            // A roleless concept carries none of the structural predicates an overlay stages, so an
            // overlay on one is a programming error rather than a no-op.
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
}
