package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.LinkSnapshotDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.NkdConceptSnapshotEntity;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.DeviationStatus;
import com.dia.ismdtoolbackend.outbox.OutboxConfig;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.NkdConceptSnapshotRepository;
import com.dia.ismdtoolbackend.service.NkdSnapshotEndpointService;
import com.dia.ismdtoolbackend.service.NkdSnapshotService;
import com.dia.ismdtoolbackend.service.snapshot.LinkSnapshotAssembler;
import com.dia.ismdtoolbackend.service.snapshot.OwnerChangeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * See {@link NkdSnapshotEndpointService}. Owns the owner-keyed outbox flush for the UPDATE/REMOVE
 * endpoints, mirroring {@code NkdSnapshotOwnerWarmer}'s flush pattern.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NkdSnapshotEndpointServiceImpl implements NkdSnapshotEndpointService {

    private final NkdConceptSnapshotRepository snapshotRepository;
    private final NkdSnapshotService nkdSnapshotService;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final OutboxConfig outboxConfig;
    private final OutboxWriter outboxWriter;
    private final OutboxRelayTrigger outboxRelayTrigger;

    @Override
    @Transactional
    public LinkSnapshotDto updateSnapshot(Long conceptId, Long snapshotId) {
        NkdConceptSnapshotEntity snapshot = loadOwnedSnapshot(conceptId, snapshotId);
        ConceptMetadataEntity owner = snapshot.getOwningConcept();
        String graphName = snapshot.getGraphName();
        String nkdIri = snapshot.getNkdIri();

        OwnerChangeSet cs = new OwnerChangeSet();

        // Re-snapshot (best-effort fetch inside the service). Returns null when NKD has no such concept
        // (confirmed gone) — the service already folded the copy-removal into cs; we also drop the owner's
        // dangling link triple here (we hold the owner-graph view, the service doesn't).
        NkdConceptSnapshotEntity refreshed =
                nkdSnapshotService.createOrRefreshSnapshot(owner, nkdIri, snapshot.getLinkPredicate(), cs);

        if (refreshed == null) {
            // Upstream-deletion cascade: also remove the owner→nkdIri link edges.
            addOwnerLinkTriplesToRemove(graphName, owner.getConceptIri(), nkdIri, cs);
            flush(graphName, owner, cs);
            log.info("UPDATE on snapshot {} found NKD concept {} gone — link + copy removed", snapshotId, nkdIri);
            return LinkSnapshotAssembler.fromFreshDeviation(snapshot,
                    PublishedConceptDeviationModel.builder()
                            .status(DeviationStatus.CONCEPT_NOT_FOUND_IN_NKD).build());
        }

        // Evaluate deviation live (the endpoint wants the full per-field diff, unlike the warm path).
        PublishedConceptDeviationModel deviation = nkdSnapshotService.evaluateDeviation(refreshed);
        flush(graphName, owner, cs);
        return LinkSnapshotAssembler.fromFreshDeviation(refreshed, deviation);
    }

    @Override
    @Transactional
    public void removeSnapshot(Long conceptId, Long snapshotId) {
        NkdConceptSnapshotEntity snapshot = loadOwnedSnapshot(conceptId, snapshotId);
        ConceptMetadataEntity owner = snapshot.getOwningConcept();
        String graphName = snapshot.getGraphName();

        OwnerChangeSet cs = new OwnerChangeSet();
        Set<Statement> ownerOutgoing = ownerOutgoingStatements(graphName, owner.getConceptIri());
        nkdSnapshotService.removeSnapshotAndLink(snapshot, ownerOutgoing, cs);
        flush(graphName, owner, cs);
        log.info("REMOVE on snapshot {} (owner {}, nkd {}) done", snapshotId, owner.getConceptIri(),
                snapshot.getNkdIri());
    }

    /** Loads the snapshot and asserts it belongs to {@code conceptId} (path/row consistency, not auth). */
    private NkdConceptSnapshotEntity loadOwnedSnapshot(Long conceptId, Long snapshotId) {
        NkdConceptSnapshotEntity snapshot = snapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new OntologyValidationException(
                        "Lokální kopie s id " + snapshotId + " nebyla nalezena."));
        if (snapshot.getOwningConcept() == null || !conceptId.equals(snapshot.getOwningConcept().getId())) {
            throw new OntologyValidationException(
                    "Lokální kopie " + snapshotId + " nepatří k pojmu " + conceptId + ".");
        }
        return snapshot;
    }

    /** Flush the owner change set as one owner-keyed aggregate: outbox upsert, or direct delta when off. */
    private void flush(String graphName, ConceptMetadataEntity owner, OwnerChangeSet cs) {
        if (cs.toRemove.isEmpty() && cs.toAdd.isEmpty()) {
            return;
        }
        touchOwner(owner);
        String ownerIri = owner.getConceptIri();
        if (outboxConfig.isEnabled()) {
            outboxWriter.enqueueUpsert(graphName, ownerIri, cs.toRemove, cs.toAdd);
            outboxRelayTrigger.nudgeAfterCommit();
            return;
        }
        Model removeModel = ModelFactory.createDefaultModel().add(new ArrayList<>(cs.toRemove));
        Model addModel = ModelFactory.createDefaultModel().add(new ArrayList<>(cs.toAdd));
        jenaTDB2Repository.applyConceptDelta(ownerIri, graphName, removeModel, addModel);
    }

    /** Stamp the owner's {@code updatedAt} so the concept row reflects the RDF change just made. */
    private void touchOwner(ConceptMetadataEntity owner) {
        owner.setUpdatedAt(LocalDateTime.now());
        conceptMetadataRepository.save(owner);
    }

    /** All of the owner concept's current outgoing statements in its graph (for unlink triple removal). */
    private Set<Statement> ownerOutgoingStatements(String graphName, String ownerIri) {
        Model model = jenaTDB2Repository.fetchGraph(graphName);
        Set<Statement> out = new HashSet<>();
        Resource ownerRes = model.getResource(ownerIri);
        StmtIterator it = model.listStatements(ownerRes, null, (RDFNode) null);
        try {
            while (it.hasNext()) {
                out.add(it.next());
            }
        } finally {
            it.close();
        }
        return out;
    }

    /** Adds the owner→nkdIri link edges (all predicates) to the remove set — used by the upstream cascade. */
    private void addOwnerLinkTriplesToRemove(String graphName, String ownerIri, String nkdIri, OwnerChangeSet cs) {
        for (Statement stmt : ownerOutgoingStatements(graphName, ownerIri)) {
            RDFNode object = stmt.getObject();
            if (object.isURIResource() && object.asResource().getURI().equals(nkdIri)) {
                cs.toRemove.add(stmt);
            }
        }
    }
}