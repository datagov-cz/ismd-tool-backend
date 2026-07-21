package com.dia.ismdtoolbackend.service.snapshot;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.service.snapshot.NkdLinkDetector.LinkTarget;
import com.dia.ismdtoolbackend.service.snapshot.NkdSnapshotOwnerWarmer.Target;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Off-request-thread warmer that populates NKD local-copy snapshots for a graph so heavy reads
 * (ontology detail) never block on NKD. Single warmer behind two triggers — cold/stale ontology
 * detail and post-commit upload.
 * <p>
 * Orchestrator only — no transaction here. Detection (graph read) and the batch
 * published-check are non-transactional; each owner is then snapshotted+flushed in its own
 * {@code REQUIRES_NEW} transaction by {@link NkdSnapshotOwnerWarmer}. That isolation is deliberate
 * (see {@link NkdSnapshotOwnerWarmer}): it prevents one poisoned target from rolling back the whole
 * graph's batch via the Spring rollback-only trap, and keeps each owner's row-writes + outbox enqueue
 * atomic.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NkdSnapshotWarmer {

    private final ConceptMetadataRepository conceptMetadataRepository;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final NkdSparqlClient nkdSparqlClient;
    private final NkdSnapshotOwnerWarmer ownerWarmer;
    private final NkdLinkDetector linkDetector;

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

        // A working copy (a locally-owned concept whose own IRI is in NKD) reads as external to the
        // detector and is published, so without this exclusion the warmer would snapshot it as a copy of
        // someone else's concept — and fight the edit hook, which excludes it (see reconcileNkdLinks).
        // `owners` is every concept in the graph, so ownership needs no extra query.
        Set<String> locallyOwnedIris = owners.stream()
                .map(ConceptMetadataEntity::getConceptIri)
                .collect(Collectors.toSet());

        // 1. Collect candidate (owner, target, linkType) — external (allowed), non-owned targets only.
        Map<Long, ConceptMetadataEntity> ownerById = new LinkedHashMap<>();
        Map<Long, List<LinkTarget>> candidatesByOwner = new LinkedHashMap<>();
        Set<String> allTargets = new HashSet<>();
        for (ConceptMetadataEntity owner : owners) {
            List<LinkTarget> ownerCandidates = linkDetector.allowedTargets(
                            owner.getConceptIri(), owner.getConceptType(), owner.getGraphName(), model)
                    .stream()
                    .filter(c -> !locallyOwnedIris.contains(c.targetIri()))
                    .toList();
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

        // 2. One batch NKD round-trip
        Set<String> published = new HashSet<>(nkdSparqlClient.getPublishedResourcesList(new ArrayList<>(allTargets)));
        if (published.isEmpty()) {
            log.debug("No external link-targets in graph {} are published in NKD", graphName);
            return;
        }

        // 3. Per owner: snapshot its published targets + flush, in its OWN transaction (isolated).
        int warmedOwners = 0;
        for (Map.Entry<Long, List<LinkTarget>> entry : candidatesByOwner.entrySet()) {
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
                log.debug("Skipped warming owner {} in {}: {}", owner.getConceptIri(), graphName, e.getMessage());
            }
        }
        log.info("Warmed NKD snapshots for graph {} ({} owner(s) materialized)", graphName, warmedOwners);
    }
}
