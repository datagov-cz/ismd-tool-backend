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
import com.dia.ismdtoolbackend.utility.published.NkdSnapshotMaterializer;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Statement;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * See {@link NkdSnapshotService}. All triple-producing methods contribute to a caller-owned
 * {@link OwnerChangeSet}; the caller performs the single owner-keyed outbox flush (C1).
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
        // C3: enforcement lives here, not in ConceptEditValidator (which is dependency-free/sync).
        if (!SnapshotLinkType.isAllowed(linkType)) {
            throw new OntologyException("Link to a published NKD concept is allowed only for "
                    + java.util.Arrays.toString(SnapshotLinkType.values()) + ", not '" + linkType + "'.");
        }
        if (!SparqlIriValidator.isSafeHttpIri(nkdIri)) {
            throw new OntologyException("Unsafe NKD IRI for snapshot: " + nkdIri);
        }

        String graphName = owner.getGraphName();
        Optional<NkdConceptSnapshotEntity> existingOpt =
                snapshotRepository.findByOwningConceptIdAndNkdIri(owner.getId(), nkdIri);

        // Best-effort NKD fetch (detail for the deviation baseline + raw triples for materialization).
        Optional<NkdSparqlClient.PublishedConcept> publishedOpt =
                nkdSparqlClient.fetchPublishedConceptWithScheme(nkdIri);
        if (publishedOpt.isEmpty()) {
            // Upstream gone (or unreachable): if we already tracked it, drop the snapshot + its copy
            // (the owner's dangling link triple is removed by the edit's own change set / the unlink
            // endpoint — create/refresh has no owner-graph view to enumerate it). If we never tracked
            // it, nothing to do.
            existingOpt.ifPresent(snapshot -> removeSnapshotCopyAndRow(snapshot, ownerChangeSet));
            log.info("NKD has no concept {} — no snapshot created (existing removed if any)", nkdIri);
            return null;
        }

        Optional<Model> rawOpt = nkdSparqlClient.fetchPublishedConceptRaw(nkdIri);
        if (rawOpt.isEmpty()) {
            log.warn("NKD concept {} resolved but raw triples unavailable — skipping materialization", nkdIri);
            return existingOpt.orElse(null);
        }

        // Materialize (pure) — owner scheme is the graph IRI (ConceptCreator writes inScheme = graph).
        Set<Statement> newTriples = materializer.materialize(rawOpt.get(), nkdIri, owner.getConceptIri(), graphName);

        NkdConceptSnapshotEntity snapshot = existingOpt.orElseGet(() -> {
            NkdConceptSnapshotEntity fresh = new NkdConceptSnapshotEntity();
            fresh.setOwningConcept(owner);
            fresh.setNkdIri(nkdIri);
            fresh.setOrigin(SnapshotOrigin.LINK_TARGET);
            return fresh;
        });

        // M1: the delete-set is the EXACT previously-stored set, never recomputed from snapshot JSON.
        ownerChangeSet.toRemove.addAll(materializer.parse(snapshot.getMaterializedTriples()));
        ownerChangeSet.toAdd.addAll(newTriples);

        snapshot.setGraphName(graphName);
        snapshot.setLinkPredicate(linkType);
        snapshot.setSnapshot(publishedOpt.get().detail());
        snapshot.setMaterializedTriples(materializer.toNTriples(newTriples));
        snapshot.setSnapshotAt(Instant.now());

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
                // Upstream deletion — caller cascades removal in a write tx (see lifecycle hooks).
                return error(DeviationStatus.CONCEPT_NOT_FOUND_IN_NKD, "Concept not found in NKD");
            }
            // LINK_TARGET: local = the stored snapshot (the copy), published = live NKD.
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
     * Drops the materialized copy (C2 refcount-gated) and deletes the PG row. Does NOT touch the
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
