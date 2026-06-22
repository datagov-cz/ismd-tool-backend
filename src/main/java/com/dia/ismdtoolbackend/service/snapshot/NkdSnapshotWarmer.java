package com.dia.ismdtoolbackend.service.snapshot;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.NkdConceptSnapshotEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.SnapshotLinkType;
import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.outbox.OutboxConfig;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.service.NkdSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>Per the C4 constraint, callers only <em>trigger</em> this; it runs on its own thread (the
 * {@code snapshotExecutor}) in its own transaction, so {@code OutboxWriter}'s active-tx assertion
 * holds and concurrent warms of the same graph each commit independently (the unique constraint on
 * {@code (owning_concept_id, nkd_iri)} makes a double-create a no-op on the loser — caught and
 * logged, not fatal).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NkdSnapshotWarmer {

    private final ConceptMetadataRepository conceptMetadataRepository;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final NkdSparqlClient nkdSparqlClient;
    private final NkdSnapshotService nkdSnapshotService;
    private final OutboxConfig outboxConfig;
    private final OutboxWriter outboxWriter;
    private final OutboxRelayTrigger outboxRelayTrigger;

    /**
     * Detect each owner concept's external (non-owned) link-targets among the allowed predicates,
     * batch-verify which are published in NKD, and snapshot the published ones — one owner-keyed
     * outbox flush per concept (C1). Best-effort: NKD/Fuseki failures leave the graph cold for the
     * next read to retry. Runs async on {@code snapshotExecutor}.
     */
    @Async("snapshotExecutor")
    @Transactional
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

        // 1. Collect candidate (owner, target, linkType) triples — external targets only.
        List<LinkCandidate> candidates = new ArrayList<>();
        Set<String> allTargets = new HashSet<>();
        for (ConceptMetadataEntity owner : owners) {
            for (LinkCandidate c : externalLinkTargets(owner, model)) {
                candidates.add(c);
                allTargets.add(c.targetIri());
            }
        }
        if (candidates.isEmpty()) {
            return;
        }

        // 2. One batch NKD round-trip: which targets are actually published?
        Set<String> published = new HashSet<>(nkdSparqlClient.getPublishedResourcesList(new ArrayList<>(allTargets)));
        if (published.isEmpty()) {
            log.debug("No external link-targets in graph {} are published in NKD", graphName);
            return;
        }

        // 3. Snapshot per published target, flushing one owner-keyed upsert per owner concept (C1).
        Map<Long, OwnerChangeSet> changeSetsByOwner = new LinkedHashMap<>();
        Map<Long, ConceptMetadataEntity> ownerById = new LinkedHashMap<>();
        for (LinkCandidate c : candidates) {
            if (!published.contains(c.targetIri())) {
                continue;
            }
            ownerById.putIfAbsent(c.owner().getId(), c.owner());
            OwnerChangeSet cs = changeSetsByOwner.computeIfAbsent(c.owner().getId(), k -> new OwnerChangeSet());
            try {
                NkdConceptSnapshotEntity snapshot =
                        nkdSnapshotService.createOrRefreshSnapshot(c.owner(), c.targetIri(), c.linkType().value(), cs);
                if (snapshot != null) {
                    nkdSnapshotService.evaluateDeviation(snapshot);
                }
            } catch (Exception e) {
                // Per-target failure (e.g. concurrent warm hit the unique constraint) — skip, non-fatal.
                log.debug("Skipped snapshot for owner {} -> {}: {}",
                        c.owner().getConceptIri(), c.targetIri(), e.getMessage());
            }
        }

        // 4. Flush each owner's accumulated copy triples as ONE owner-keyed outbox upsert.
        boolean anyEnqueued = false;
        for (Map.Entry<Long, OwnerChangeSet> entry : changeSetsByOwner.entrySet()) {
            OwnerChangeSet cs = entry.getValue();
            if (cs.toRemove.isEmpty() && cs.toAdd.isEmpty()) {
                continue;
            }
            ConceptMetadataEntity owner = ownerById.get(entry.getKey());
            if (outboxConfig.isEnabled()) {
                outboxWriter.enqueueUpsert(graphName, owner.getConceptIri(), cs.toRemove, cs.toAdd);
                anyEnqueued = true;
            } else {
                // outbox-disabled fallback: direct concept-scoped delta (same shape the outbox relay applies).
                Model removeModel = ModelFactory.createDefaultModel().add(new ArrayList<>(cs.toRemove));
                Model addModel = ModelFactory.createDefaultModel().add(new ArrayList<>(cs.toAdd));
                jenaTDB2Repository.applyConceptDelta(owner.getConceptIri(), graphName, removeModel, addModel);
            }
        }
        if (anyEnqueued) {
            outboxRelayTrigger.nudgeAfterCommit();
        }
        log.info("Warmed NKD snapshots for graph {} ({} owner(s) materialized)", graphName, changeSetsByOwner.size());
    }

    /** The external (non-owned) link-targets of one owner concept, with their logical link type. */
    private List<LinkCandidate> externalLinkTargets(ConceptMetadataEntity owner, Model model) {
        String ownerIri = owner.getConceptIri();
        String graphScheme = owner.getGraphName();
        Resource ownerRes = model.getResource(ownerIri);
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
