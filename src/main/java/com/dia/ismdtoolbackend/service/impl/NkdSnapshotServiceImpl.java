package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.NkdConceptSnapshotEntity;
import com.dia.ismdtoolbackend.enums.SnapshotLinkType;
import com.dia.ismdtoolbackend.enums.SnapshotOrigin;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.DeviationStatus;
import com.dia.ismdtoolbackend.repository.NkdConceptSnapshotRepository;
import com.dia.ismdtoolbackend.service.NkdSnapshotService;
import com.dia.ismdtoolbackend.service.snapshot.OwnerChangeSet;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.utility.published.NkdSnapshotMaterializer;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Statement;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * See {@link NkdSnapshotService}. All triple-producing methods contribute to a caller-owned
 * {@link OwnerChangeSet}; the caller performs the single owner-keyed outbox flush.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NkdSnapshotServiceImpl implements NkdSnapshotService {

    private final NkdConceptSnapshotRepository snapshotRepository;
    private final NkdSparqlClient nkdSparqlClient;
    private final NkdSnapshotMaterializer materializer;
    private final ConceptDeviationComparator conceptDeviationComparator;

    @Override
    @Transactional
    public NkdConceptSnapshotEntity createOrRefreshSnapshot(ConceptMetadataEntity owner, String nkdIri,
                                                            String linkType, OwnerChangeSet ownerChangeSet) {
        if (!SnapshotLinkType.isAllowed(linkType)) {
            throw new OntologyValidationException("Link to a published NKD concept is allowed only for "
                    + java.util.Arrays.toString(SnapshotLinkType.values()) + ", not '" + linkType + "'.");
        }
        if (!SparqlIriValidator.isSafeHttpIri(nkdIri)) {
            throw new OntologyValidationException("Unsafe NKD IRI for snapshot: " + nkdIri);
        }

        String graphName = owner.getGraphName();
        Optional<NkdConceptSnapshotEntity> existingOpt =
                snapshotRepository.findByOwningConceptIdAndNkdIri(owner.getId(), nkdIri);

        // Outage (unreachable) vs confirmed-absent (reachable, returns nothing) are different: an outage
        // must leave the existing snapshot intact and not roll back the edit, whereas a confirmed-absent
        // concept means the link target is gone upstream and the tracked copy should be removed.
        Optional<NkdSparqlClient.PublishedConcept> publishedOpt;
        try {
            publishedOpt = nkdSparqlClient.fetchPublishedConceptWithScheme(nkdIri);
        } catch (SparqlEndpointUnavailableException e) {
            log.warn("NKD unavailable while snapshotting {} — skipping, existing snapshot kept: {}",
                    nkdIri, e.getMessage());
            return existingOpt.orElse(null);
        }
        if (publishedOpt.isEmpty()) {
            existingOpt.ifPresent(snapshot -> removeSnapshotCopyAndRow(snapshot, ownerChangeSet));
            log.info("NKD has no concept {} — no snapshot created (existing removed if any)", nkdIri);
            return null;
        }

        Optional<Model> rawOpt = nkdSparqlClient.fetchPublishedConceptRaw(nkdIri);
        if (rawOpt.isEmpty()) {
            log.warn("NKD concept {} resolved but raw triples unavailable — skipping materialization", nkdIri);
            return existingOpt.orElse(null);
        }

        Set<Statement> newTriples = materializer.materialize(rawOpt.get(), nkdIri, owner.getConceptIri(), graphName);

        NkdConceptSnapshotEntity snapshot = existingOpt.orElseGet(() -> {
            NkdConceptSnapshotEntity fresh = new NkdConceptSnapshotEntity();
            fresh.setOwningConcept(owner);
            fresh.setNkdIri(nkdIri);
            fresh.setOrigin(SnapshotOrigin.LINK_TARGET);
            return fresh;
        });

        ownerChangeSet.toRemove.addAll(materializer.parse(snapshot.getMaterializedTriples()));
        ownerChangeSet.toAdd.addAll(newTriples);

        Instant now = Instant.now();
        snapshot.setGraphName(graphName);
        snapshot.setLinkPredicate(linkType);
        snapshot.setSnapshot(publishedOpt.get().detail());
        snapshot.setMaterializedTriples(materializer.toNTriples(newTriples));
        snapshot.setSnapshotAt(now);
        snapshot.setLastDeviationStatus(DeviationStatus.NO_DEVIATION);
        snapshot.setLastCheckedAt(now);

        NkdConceptSnapshotEntity saved = snapshotRepository.save(snapshot);
        log.debug("Snapshotted NKD copy {} for owner {} ({} triples)", nkdIri, owner.getConceptIri(), newTriples.size());
        return saved;
    }

    @Override
    @Transactional
    public PublishedConceptDeviationModel evaluateDeviation(NkdConceptSnapshotEntity snapshot) {
        PublishedConceptDeviationModel result = computeDeviation(snapshot);
        snapshot.setLastDeviationStatus(result.getStatus());
        snapshot.setLastCheckedAt(Instant.now());
        snapshotRepository.save(snapshot);
        return result;
    }

    private PublishedConceptDeviationModel computeDeviation(NkdConceptSnapshotEntity snapshot) {
        ConceptDetailModel local = snapshot.getSnapshot();
        if (local == null) {
            return error(DeviationStatus.QUERY_ERROR, "Stored snapshot detail unavailable");
        }
        try {
            Optional<ConceptDetailModel> publishedOpt = nkdSparqlClient.fetchPublishedConcept(snapshot.getNkdIri());
            if (publishedOpt.isEmpty()) {
                return error(DeviationStatus.CONCEPT_NOT_FOUND_IN_NKD, "Concept not found in NKD");
            }
            return conceptDeviationComparator.compareConceptDetails(local, publishedOpt.get());
        } catch (Exception e) {
            log.error("Deviation check failed for snapshot {}: {}", snapshot.getNkdIri(), e.getMessage(), e);
            return error(DeviationStatus.ENDPOINT_UNAVAILABLE, "NKD unavailable: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public void removeSnapshotAndLink(NkdConceptSnapshotEntity snapshot, Set<Statement> ownerOutgoingStatements,
                                      OwnerChangeSet ownerChangeSet) {
        // Remove ALL owner->nkdIri link triples regardless of predicate (broaderClass writes both
        // rdfs:subClassOf AND the namespaced hierarchy prop) — "unlink" = drop every edge to the target.
        String nkdIri = snapshot.getNkdIri();
        if (ownerOutgoingStatements != null) {
            for (Statement stmt : ownerOutgoingStatements) {
                RDFNode object = stmt.getObject();
                if (object.isURIResource() && object.asResource().getURI().equals(nkdIri)) {
                    ownerChangeSet.toRemove.add(stmt);
                }
            }
        }
        removeSnapshotCopyAndRow(snapshot, ownerChangeSet);
    }

    /**
     * Drops the materialized copy and deletes the PG row. Does NOT touch the
     * owner's link triple — that is handled by the caller (which holds the owner-graph view) or, in
     * the create/refresh cascade, by the edit's own change set.
     */
    private void removeSnapshotCopyAndRow(NkdConceptSnapshotEntity snapshot, OwnerChangeSet ownerChangeSet) {
        long referrers = snapshotRepository.countByGraphNameAndNkdIri(snapshot.getGraphName(), snapshot.getNkdIri());
        if (referrers <= 1) {
            ownerChangeSet.toRemove.addAll(materializer.parse(snapshot.getMaterializedTriples()));
        } else {
            log.debug("NKD copy {} still referenced by {} other concept(s) in {} — keeping materialized triples",
                    snapshot.getNkdIri(), referrers - 1, snapshot.getGraphName());
        }
        snapshotRepository.delete(snapshot);
        log.debug("Removed snapshot row for NKD copy {} (owner {})",
                snapshot.getNkdIri(), snapshot.getOwningConcept().getConceptIri());
    }

    @Override
    @Transactional
    public List<String> cascadeConceptDeletion(List<Long> deletedConceptIds, String graphName) {
        if (deletedConceptIds == null || deletedConceptIds.isEmpty()) {
            return List.of();
        }
        List<NkdConceptSnapshotEntity> rows = deletedConceptIds.stream()
                .flatMap(id -> snapshotRepository.findByOwningConceptId(id).stream())
                .filter(s -> graphName.equals(s.getGraphName()))
                .toList();
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<String, Long> droppedByIri = rows.stream()
                .collect(Collectors.groupingBy(NkdConceptSnapshotEntity::getNkdIri, Collectors.counting()));

        List<String> orphanedCopies = new ArrayList<>();
        for (Map.Entry<String, Long> e : droppedByIri.entrySet()) {
            long live = snapshotRepository.countByGraphNameAndNkdIri(graphName, e.getKey());
            if (live - e.getValue() <= 0) {
                orphanedCopies.add(e.getKey());
            } else {
                log.debug("NKD copy {} keeps {} surviving referrer(s) after delete in {} — copy retained",
                        e.getKey(), live - e.getValue(), graphName);
            }
        }

        snapshotRepository.deleteAll(rows);
        log.debug("Concept-deletion cascade: removed {} snapshot row(s), {} orphaned copy(ies) in {}",
                rows.size(), orphanedCopies.size(), graphName);
        return orphanedCopies;
    }

    @Override
    @Transactional
    public void cascadeGraphDeletion(String graphName) {
        List<NkdConceptSnapshotEntity> rows = snapshotRepository.findByGraphName(graphName);
        if (!rows.isEmpty()) {
            snapshotRepository.deleteAll(rows);
            log.debug("Ontology-deletion cascade: removed {} snapshot row(s) for graph {}", rows.size(), graphName);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<NkdConceptSnapshotEntity> findForConcept(Long conceptId) {
        return snapshotRepository.findByOwningConceptId(conceptId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NkdConceptSnapshotEntity> findForGraph(String graphName) {
        return snapshotRepository.findByGraphName(graphName);
    }

    private static PublishedConceptDeviationModel error(DeviationStatus status, String message) {
        return PublishedConceptDeviationModel.builder().status(status).errorMessage(message).build();
    }
}
