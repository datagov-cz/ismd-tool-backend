package com.dia.ismdtoolbackend.service.snapshot;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.config.NkdConfig;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private final NkdConfig nkdConfig;

    /**
     * Last time a scan was STARTED per graph, so a detail read that finds no snapshot rows does not
     * re-scan the whole graph on every load.
     *
     * <p>An ontology whose external links are not published in NKD legitimately keeps zero rows
     * forever, so {@code rows.isEmpty()} stays true and the read-side trigger fires every time. The
     * scan itself is not free — it materializes the entire graph out of TDB2 — so without this the
     * snapshot executor and Fuseki take that load on every detail view. Keyed by graph name and
     * bounded; entries are only ever overwritten or evicted, never read for correctness.
     */
    private final Map<String, Instant> lastScanStartedByGraph = new ConcurrentHashMap<>();

    /** Bounds the marker map so a large catalogue cannot grow it without limit. */
    private static final int MAX_TRACKED_GRAPHS = 10_000;

    /**
     * Detect each owner concept's external (non-owned) link-targets among the allowed predicates,
     * batch-verify which are published in NKD, and snapshot the published ones — one isolated
     * {@code REQUIRES_NEW} transaction per owner. Best-effort: NKD/Fuseki failures leave the graph
     * cold for the next read to retry. Runs async on {@code snapshotExecutor}.
     *
     * <p>Throttled per graph by the snapshot deviation TTL — see {@link #lastScanStartedByGraph}.
     * Use {@link #warmGraphNow} when the graph is known to have changed.
     */
    @Async("snapshotExecutor")
    public void warmGraph(String graphName) {
        if (!claimScanSlot(graphName)) {
            log.debug("Skipping NKD snapshot scan for graph {} — scanned within the TTL", graphName);
            return;
        }
        runWarm(graphName);
    }

    /**
     * Unthrottled warm for callers that know the graph just changed (e.g. post-upload), so a fresh
     * scan is never suppressed by a marker left by an earlier read.
     */
    @Async("snapshotExecutor")
    public void warmGraphNow(String graphName) {
        lastScanStartedByGraph.put(graphName, Instant.now());
        runWarm(graphName);
    }

    private void runWarm(String graphName) {
        try {
            warmGraphInternal(graphName);
        } catch (Exception e) {
            log.warn("NKD snapshot warming failed for graph {}: {}", graphName, e.getMessage(), e);
        }
    }

    /**
     * Marks the scan as started (before the expensive graph read) and reports whether this call owns
     * it. Recording the START rather than the completion is what stops concurrent detail loads from
     * all scanning the same cold graph at once.
     */
    private boolean claimScanSlot(String graphName) {
        Instant now = Instant.now();
        Duration ttl = nkdConfig.getSnapshot().getDeviationTtl();
        if (lastScanStartedByGraph.size() >= MAX_TRACKED_GRAPHS) {
            lastScanStartedByGraph.clear();
        }
        Instant previous = lastScanStartedByGraph.get(graphName);
        if (previous != null && ttl != null && previous.plus(ttl).isAfter(now)) {
            return false;
        }
        // A losing racer sees the winner's timestamp and backs off.
        return lastScanStartedByGraph.put(graphName, now) == previous;
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
