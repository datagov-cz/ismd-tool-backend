package com.dia.ismdtoolbackend.service.snapshot;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.SnapshotLinkType;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.service.snapshot.NkdSnapshotOwnerWarmer.Target;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Off-request-thread warmer that populates NKD local-copy snapshots for a graph so heavy reads
 * (ontology detail) never block on NKD. Single warmer behind two triggers — cold/stale ontology
 * detail and post-commit upload.
 *
 * <p>Orchestrator only — no transaction here. Detection (graph read) and the batch
 * published-check are non-transactional; each owner is then snapshotted+flushed in its own
 * {@code REQUIRES_NEW} transaction by {@link NkdSnapshotOwnerWarmer}. That isolation is deliberate
 * (see {@link NkdSnapshotOwnerWarmer}): it prevents one poisoned target from rolling back the whole
 * graph's batch via the Spring rollback-only trap, and keeps each owner's row-writes + outbox enqueue
 * atomic (C1).
 *
 * <p>Per the C4 constraint, callers only <em>trigger</em> this; it runs on its own thread (the
 * {@code snapshotExecutor}).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NkdSnapshotWarmer {

    private final ConceptMetadataRepository conceptMetadataRepository;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final NkdSparqlClient nkdSparqlClient;
    private final NkdSnapshotOwnerWarmer ownerWarmer;

    /**
     * Detect each owner concept's external (non-owned) link-targets among the allowed predicates,
     * batch-verify which are published in NKD, and snapshot the published ones — one isolated
     * {@code REQUIRES_NEW} transaction per owner. Best-effort: NKD/Fuseki failures leave the graph
     * cold for the next read to retry. Runs async on {@code snapshotExecutor}.
     */
    @Async("snapshotExecutor")
    public void warmGraph(String graphName) {
        try {
            warmGraphInternal(graphName);
        } catch (Exception e) {
            // Never propagate from an async warmer — the graph just stays cold and a later read retries.
            log.warn("NKD snapshot warming failed for graph {}: {}", graphName, e.getMessage(), e);
        }
    }

    private void warmGraphInternal(String graphName) {
        List<ConceptMetadataEntity> owners = conceptMetadataRepository.findByGraphName(graphName);
        if (owners.isEmpty()) {
            return;
        }
        if (!jenaTDB2Repository.graphHasData(graphName)) {
            log.debug("Graph {} has no data — nothing to warm", graphName);
            return;
        }

        Model model = jenaTDB2Repository.fetchGraph(graphName);

        // 1. Collect candidate (owner, target, linkType) — external targets only.
        Map<Long, ConceptMetadataEntity> ownerById = new LinkedHashMap<>();
        Map<Long, List<LinkCandidate>> candidatesByOwner = new LinkedHashMap<>();
        Set<String> allTargets = new HashSet<>();
        for (ConceptMetadataEntity owner : owners) {
            List<LinkCandidate> ownerCandidates = externalLinkTargets(owner, model);
            if (ownerCandidates.isEmpty()) {
                continue;
            }
            ownerById.put(owner.getId(), owner);
            candidatesByOwner.put(owner.getId(), ownerCandidates);
            ownerCandidates.forEach(c -> allTargets.add(c.targetIri()));
        }
        if (allTargets.isEmpty()) {
            return;
        }

        // 2. One batch NKD round-trip: which targets are actually published?
        Set<String> published = new HashSet<>(nkdSparqlClient.getPublishedResourcesList(new ArrayList<>(allTargets)));
        if (published.isEmpty()) {
            log.debug("No external link-targets in graph {} are published in NKD", graphName);
            return;
        }

        // 3. Per owner: snapshot its published targets + flush, in its OWN transaction (isolated).
        int warmedOwners = 0;
        for (Map.Entry<Long, List<LinkCandidate>> entry : candidatesByOwner.entrySet()) {
            List<Target> targets = entry.getValue().stream()
                    .filter(c -> published.contains(c.targetIri()))
                    .map(c -> new Target(c.targetIri(), c.linkType().value()))
                    .toList();
            if (targets.isEmpty()) {
                continue;
            }
            ConceptMetadataEntity owner = ownerById.get(entry.getKey());
            try {
                if (ownerWarmer.warmOwner(graphName, owner, targets)) {
                    warmedOwners++;
                }
            } catch (Exception e) {
                // The owner's REQUIRES_NEW tx already rolled back in isolation — losing only this owner,
                // not the batch. Skip and let a later read retry it.
                log.debug("Skipped warming owner {} in {}: {}", owner.getConceptIri(), graphName, e.getMessage());
            }
        }
        log.info("Warmed NKD snapshots for graph {} ({} owner(s) materialized)", graphName, warmedOwners);
    }

    /** The external (non-owned) link-targets of one owner concept, with their logical link type. */
    private List<LinkCandidate> externalLinkTargets(ConceptMetadataEntity owner, Model model) {
        String graphScheme = owner.getGraphName();
        Resource ownerRes = model.getResource(owner.getConceptIri());
        List<LinkCandidate> out = new ArrayList<>();

        // subClassOf / subPropertyOf — meaning depends on the owner's concept type.
        SnapshotLinkType hierarchyType = hierarchyLinkType(owner.getConceptType());
        if (hierarchyType != null) {
            Property hierarchyPred = hierarchyType == SnapshotLinkType.BROADER_CLASS
                    ? RDFS.subClassOf : RDFS.subPropertyOf;
            collectExternalObjects(owner, ownerRes, hierarchyPred, graphScheme, hierarchyType, out);
        }
        // exactMatch — any concept type.
        collectExternalObjects(owner, ownerRes, SKOS.exactMatch, graphScheme, SnapshotLinkType.EXACT_MATCH, out);
        return out;
    }

    private void collectExternalObjects(ConceptMetadataEntity owner, Resource ownerRes, Property predicate,
                                        String graphScheme, SnapshotLinkType linkType, List<LinkCandidate> out) {
        StmtIterator it = ownerRes.getModel().listStatements(ownerRes, predicate, (RDFNode) null);
        try {
            while (it.hasNext()) {
                RDFNode object = it.next().getObject();
                if (!object.isURIResource()) {
                    continue;
                }
                String targetIri = object.asResource().getURI();
                // External = not owned by this graph's scheme (the OWNED_CONCEPT_PATTERN prefix rule).
                if (graphScheme != null && targetIri.startsWith(graphScheme)) {
                    continue;
                }
                out.add(new LinkCandidate(owner, targetIri, linkType));
            }
        } finally {
            it.close();
        }
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

    private record LinkCandidate(ConceptMetadataEntity owner, String targetIri, SnapshotLinkType linkType) {}
}
