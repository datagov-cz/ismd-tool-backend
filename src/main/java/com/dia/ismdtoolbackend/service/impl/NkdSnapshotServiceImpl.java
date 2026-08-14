package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.NkdConceptRefDto;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    private final DeviationResolutionEnricher deviationEnricher;

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
            existingOpt.ifPresent(this::removeSnapshotRow);
            log.info("NKD has no concept {} — no snapshot created (existing removed if any)", nkdIri);
            return null;
        }

        Optional<Model> rawOpt = nkdSparqlClient.fetchPublishedConceptRaw(nkdIri);
        if (rawOpt.isEmpty()) {
            log.warn("NKD concept {} resolved but raw triples unavailable — skipping materialization", nkdIri);
            return existingOpt.orElse(null);
        }

        Set<Statement> newTriples = materializer.materialize(rawOpt.get(), nkdIri, owner.getConceptIri());

        NkdConceptSnapshotEntity snapshot = existingOpt.orElseGet(() -> {
            NkdConceptSnapshotEntity fresh = new NkdConceptSnapshotEntity();
            fresh.setOwningConcept(owner);
            fresh.setNkdIri(nkdIri);
            fresh.setOrigin(SnapshotOrigin.LINK_TARGET);
            return fresh;
        });

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
    public void refreshOrSeedForWarming(ConceptMetadataEntity owner, String nkdIri, String linkType,
                                        OwnerChangeSet ownerChangeSet) {
        Optional<NkdConceptSnapshotEntity> existingOpt =
                snapshotRepository.findByOwningConceptIdAndNkdIri(owner.getId(), nkdIri);
        if (existingOpt.isPresent()) {
            // A copy already exists — a read must not overwrite it. Re-evaluate only, so upstream drift
            // surfaces as HAS_DEVIATIONS instead of being silently adopted (and NO_DEVIATION-by-construction).
            evaluateDeviation(existingOpt.get());
            return;
        }
        // First time this link is seen: no frozen copy exists yet, so materialize it.
        createOrRefreshSnapshot(owner, nkdIri, linkType, ownerChangeSet);
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
            return error(snapshot, DeviationStatus.QUERY_ERROR, "Stored snapshot detail unavailable");
        }
        try {
            Optional<ConceptDetailModel> publishedOpt = nkdSparqlClient.fetchPublishedConcept(snapshot.getNkdIri());
            if (publishedOpt.isEmpty()) {
                return error(snapshot, DeviationStatus.CONCEPT_NOT_FOUND_IN_NKD, "Concept not found in NKD");
            }
            // LINK_TARGET: the stored copy is compared against the foreign NKD concept it was copied from.
            PublishedConceptDeviationModel deviation = conceptDeviationComparator.compareConceptDetails(
                    local, publishedOpt.get(), snapshot.getOrigin(), snapshot.getNkdIri());
            deviationEnricher.enrich(deviation);
            return deviation;
        } catch (SparqlEndpointUnavailableException e) {
            log.error("NKD unavailable for snapshot {}: {}", snapshot.getNkdIri(), e.getMessage());
            return error(snapshot, DeviationStatus.ENDPOINT_UNAVAILABLE, "NKD unavailable: " + e.getMessage());
        } catch (Exception e) {
            // Not an upstream outage: a bug or bad stored data. QUERY_ERROR keeps the comparison untrusted
            // without blaming NKD for a fault on our side.
            log.error("Deviation check failed for snapshot {}: {}", snapshot.getNkdIri(), e.getMessage(), e);
            return error(snapshot, DeviationStatus.QUERY_ERROR, "Deviation check failed: " + e.getMessage());
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
        removeSnapshotRow(snapshot);
    }

    /** Deletes the PG snapshot row. The copy lives only in PG, so there is no TDB2 triple to remove. */
    private void removeSnapshotRow(NkdConceptSnapshotEntity snapshot) {
        snapshotRepository.delete(snapshot);
        log.debug("Removed snapshot row for NKD copy {} (owner {})",
                snapshot.getNkdIri(), snapshot.getOwningConcept().getConceptIri());
    }

    @Override
    @Transactional
    public void cascadeConceptDeletion(List<Long> deletedConceptIds, String graphName) {
        if (deletedConceptIds == null || deletedConceptIds.isEmpty()) {
            return;
        }
        // Key on the owning-concept FK alone. The denormalized graph_name drifts on ontology rename
        // (concepts move to the new graph, snapshots keep the old one), so filtering by graphName would
        // strand rows and the concept DELETE would hit fk_nkd_snapshot_owning_concept.
        List<NkdConceptSnapshotEntity> rows = snapshotRepository.findByOwningConceptIdIn(deletedConceptIds);
        if (rows.isEmpty()) {
            return;
        }
        snapshotRepository.deleteAll(rows);
        log.debug("Concept-deletion cascade: removed {} snapshot row(s) for {} concept(s)",
                rows.size(), deletedConceptIds.size());
    }

    @Override
    @Transactional
    public void cascadeGraphDeletion(List<Long> conceptIds, String graphName) {
        // Union of owner-FK rows (drift-proof — catches snapshots whose graph_name lags a rename) and any
        // rows still tagged with the graph (belt-and-suspenders for orphans). Dedup by id before deleting.
        Map<Long, NkdConceptSnapshotEntity> rows = new LinkedHashMap<>();
        if (conceptIds != null && !conceptIds.isEmpty()) {
            snapshotRepository.findByOwningConceptIdIn(conceptIds).forEach(r -> rows.put(r.getId(), r));
        }
        snapshotRepository.findByGraphName(graphName).forEach(r -> rows.put(r.getId(), r));
        if (rows.isEmpty()) {
            return;
        }
        List<NkdConceptSnapshotEntity> toDelete = List.copyOf(rows.values());
        snapshotRepository.deleteAll(toDelete);
        log.debug("Ontology-deletion cascade: removed {} snapshot row(s) for graph {}", toDelete.size(), graphName);
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

    /**
     * An error envelope still carries {@code origin}/{@code source}: the comparison failed, but which case
     * this is and what it points at are known, and the FE needs both to render the card.
     */
    private static PublishedConceptDeviationModel error(NkdConceptSnapshotEntity snapshot,
                                                        DeviationStatus status, String message) {
        return PublishedConceptDeviationModel.builder()
                .status(status)
                .errorMessage(message)
                .origin(snapshot.getOrigin())
                .source(NkdConceptRefDto.builder().iri(snapshot.getNkdIri()).build())
                .build();
    }
}
