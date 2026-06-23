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
 * Single source of truth for "which of a concept's outgoing edges point at an external
 * (non-owned) concept, and through which relation". Shared by the async warmer (which snapshots the
 * allowed targets) and the synchronous edit hook (which also needs the C3-forbidden domain/range
 * targets to reject).
 * <p>
 * "External" = the object IRI is not prefixed by the owner graph's scheme &mdash; the same
 * {@code OWNED_CONCEPT_PATTERN} prefix rule the reconciler uses. A self-link (owned object) is never
 * a snapshot/copy candidate.
 * <p>
 * Stateless; detection is over an in-memory {@link Model} only &mdash; no I/O.
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
        return out;
    }

    /**
     * The external {@code rdfs:domain} / {@code rdfs:range} target IRIs of {@code conceptIri}. A
     * domain/range pointing at a published NKD concept is an invalid input the edit hook rejects
     * — these are never snapshot candidates, only reject candidates.
     */
    public List<String> forbiddenDomainRangeTargets(String conceptIri, String graphScheme, Model model) {
        Resource concept = model.getResource(conceptIri);
        List<String> out = new ArrayList<>();
        collectExternalObjectIris(concept, RDFS.domain, graphScheme, out);
        collectExternalObjectIris(concept, RDFS.range, graphScheme, out);
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
        };
    }
}
