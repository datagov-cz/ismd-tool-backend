package com.dia.ismdtoolbackend.service.snapshot;

import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.SnapshotLinkType;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Single source of truth for which of a concept's outgoing edges point at an external (non-owned)
 * concept, and through which relation. Shared by the async warmer (which snapshots the allowed targets)
 * and the edit hook (which also needs the forbidden domain/range targets to reject).
 *
 * <p>"External" = the object IRI is not prefixed by the owner graph's scheme. Stateless; detection is
 * over an in-memory {@link Model} only — no I/O.
 */
@Component
public class NkdLinkDetector {

    /** One external outgoing edge of a concept: the target IRI and the logical relation. */
    public record LinkTarget(String targetIri, SnapshotLinkType linkType) {
    }

    /**
     * The allowed external link-targets (broaderClass / superProperty / superRelation /
     * exactMatch) of {@code conceptIri} in {@code model}. These are the snapshot candidates.
     *
     * <p>A <strong>VZTAH</strong> additionally contributes its external {@code rdfs:range} as a
     * {@link SnapshotLinkType#RANGE_TARGET}: a relationship's range is a class, so pointing it at a
     * published NKD concept is a legitimate cross-vocabulary link, and snapshotting it is what gives the
     * diagram a local label to draw and wires the target into the upstream-deletion cascade.
     *
     * @param conceptType drives the hierarchy relation's meaning (TRIDA→broaderClass via subClassOf,
     *                    VLASTNOST→superProperty / VZTAH→superRelation via subPropertyOf). May be null.
     * @param graphScheme the owner graph's scheme; objects prefixed by it are owned, not external.
     */
    public List<LinkTarget> allowedTargets(String conceptIri, ConceptType conceptType,
                                           String graphScheme, Model model) {
        Resource concept = model.getResource(conceptIri);
        List<LinkTarget> out = new ArrayList<>();

        SnapshotLinkType hierarchyType = hierarchyLinkType(conceptType);
        if (hierarchyType != null) {
            Property hierarchyPred = hierarchyType == SnapshotLinkType.BROADER_CLASS
                    ? RDFS.subClassOf : RDFS.subPropertyOf;
            collectExternalObjects(concept, hierarchyPred, graphScheme, hierarchyType, out);
        }
        collectExternalObjects(concept, SKOS.exactMatch, graphScheme, SnapshotLinkType.EXACT_MATCH, out);
        if (conceptType == ConceptType.VZTAH) {
            collectExternalObjects(concept, RDFS.range, graphScheme, SnapshotLinkType.RANGE_TARGET, out);
        }
        return out;
    }

    /**
     * The external {@code rdfs:domain} / {@code rdfs:range} target IRIs the edit hook must reject when
     * they resolve to a published NKD concept. These are reject candidates, never snapshot candidates.
     *
     * <p><strong>{@code rdfs:domain} is collected for every type</strong> — a domain pointing at a
     * published concept is invalid input whatever the subject is.
     *
     * <p><strong>{@code rdfs:range} is collected for every type EXCEPT VZTAH.</strong> A VLASTNOST's
     * range is an XSD datatype, so a concept there is malformed; a VZTAH's range is a class, and
     * pointing it at a published NKD concept is a supported cross-vocabulary link — snapshotted as
     * {@link SnapshotLinkType#RANGE_TARGET} by {@link #allowedTargets} instead of rejected here.
     *
     * @param conceptType the subject's type; null is treated conservatively (range still collected).
     */
    public List<String> forbiddenDomainRangeTargets(String conceptIri, ConceptType conceptType,
                                                    String graphScheme, Model model) {
        Resource concept = model.getResource(conceptIri);
        List<String> out = new ArrayList<>();
        collectExternalObjectIris(concept, RDFS.domain, graphScheme, out);
        if (conceptType != ConceptType.VZTAH) {
            collectExternalObjectIris(concept, RDFS.range, graphScheme, out);
        }
        return out;
    }

    private void collectExternalObjects(Resource concept, Property predicate, String graphScheme,
                                        SnapshotLinkType linkType, List<LinkTarget> out) {
        StmtIterator it = concept.getModel().listStatements(concept, predicate, (RDFNode) null);
        try {
            while (it.hasNext()) {
                RDFNode object = it.next().getObject();
                if (isExternalUri(object, graphScheme)) {
                    out.add(new LinkTarget(object.asResource().getURI(), linkType));
                }
            }
        } finally {
            it.close();
        }
    }

    private void collectExternalObjectIris(Resource concept, Property predicate, String graphScheme,
                                           List<String> out) {
        StmtIterator it = concept.getModel().listStatements(concept, predicate, (RDFNode) null);
        try {
            while (it.hasNext()) {
                RDFNode object = it.next().getObject();
                if (isExternalUri(object, graphScheme)) {
                    out.add(object.asResource().getURI());
                }
            }
        } finally {
            it.close();
        }
    }

    /** A URI resource whose IRI is not prefixed by the owner graph's scheme. */
    private static boolean isExternalUri(RDFNode object, String graphScheme) {
        if (!object.isURIResource()) {
            return false;
        }
        String iri = object.asResource().getURI();
        return graphScheme == null || !iri.startsWith(graphScheme);
    }

    private static SnapshotLinkType hierarchyLinkType(ConceptType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case TRIDA -> SnapshotLinkType.BROADER_CLASS;
            case VLASTNOST -> SnapshotLinkType.SUPER_PROPERTY;
            case VZTAH -> SnapshotLinkType.SUPER_RELATION;
            case KONCEPT -> null;
        };
    }
}
