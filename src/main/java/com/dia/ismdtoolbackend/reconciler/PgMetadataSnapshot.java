package com.dia.ismdtoolbackend.reconciler;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads a consistent Postgres metadata snapshot for the reconciler in a single read-only
 * transaction. Lives in its own bean (not a method on {@link ConsistencyReconciler}) so the
 * {@code @Transactional} proxy actually applies — a self-invoked {@code @Transactional} method
 * would bypass the proxy and run without the shared snapshot.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PgMetadataSnapshot {

    private final ConceptMetadataRepository conceptMetadataRepository;
    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ReconcilerConfig config;

    /**
     * The PG side of one reconcile pass: every concept row, an IRI→row index, the count, and
     * the set of known ontology graph names — all from one consistent read.
     */
    public record Snapshot(
            List<ConceptMetadataEntity> concepts,
            Map<String, ConceptMetadataEntity> byIri,
            Set<String> knownGraphs
    ) {}

    @Transactional(readOnly = true)
    public Snapshot load() {
        // Abort loudly before materializing the whole table into heap. The unpaginated findAll()
        // below can OOM mid-run on a very large dataset and leave no findings and no record — a
        // silent failure. A cheap COUNT first turns that into a clear, recoverable error.
        int cap = config.getMaxConcepts();
        if (cap > 0) {
            long total = conceptMetadataRepository.count();
            if (total > cap) {
                throw new IllegalStateException(
                        "Reconcile aborted: " + total + " concept rows exceed reconciler.max-concepts=" + cap
                        + ". Raise the cap or page the snapshot before running on this dataset.");
            }
        }
        List<ConceptMetadataEntity> concepts = conceptMetadataRepository.findAll();
        Map<String, ConceptMetadataEntity> byIri = new HashMap<>();
        for (ConceptMetadataEntity c : concepts) {
            if (c.getConceptIri() != null) {
                byIri.put(c.getConceptIri(), c);
            }
        }
        Set<String> knownGraphs = new HashSet<>();
        ontologyMetadataRepository.findAll().forEach(o -> {
            if (o.getGraphName() != null) {
                knownGraphs.add(o.getGraphName());
            }
        });
        return new Snapshot(concepts, byIri, knownGraphs);
    }
}
