package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.service.NkdSnapshotService;
import com.dia.ismdtoolbackend.service.snapshot.NkdLinkDetector;
import com.dia.ismdtoolbackend.service.snapshot.OwnerChangeSet;
import com.dia.ismdtoolbackend.entity.NkdConceptSnapshotEntity;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Reconciles a concept's links to published NKD concepts against its local-copy snapshots. */
@Slf4j
@Component
@RequiredArgsConstructor
class NkdLinkReconciler {

    private final ConceptMetadataRepository conceptMetadataRepository;
    private final NkdSparqlClient nkdSparqlClient;
    private final NkdSnapshotService nkdSnapshotService;
    private final NkdLinkDetector nkdLinkDetector;

    /**
     * Reconciles this concept's links to published NKD concepts against its local-copy snapshots and
     * returns the materialized-copy delta as a fresh mutable {@link OwnerChangeSet} for the caller to
     * merge into the owner aggregate's flush. Operates on the post-edit {@code model} and owner IRI,
     * inside the edit transaction.
     *
     * <p>Returns its own change set rather than mutating {@code EditResult}'s, whose sets are immutable
     * copies. Best-effort: a transient NKD outage never rolls back the edit (the service skips); only a
     * confirmed-published domain/range link throws (HTTP 400).
     *
     * <p>{@link NkdLinkDetector} calls a target "external" when its IRI is not prefixed by the owner
     * graph's scheme, which is true of a <em>working copy</em> — a locally-owned concept whose own IRI is
     * in NKD. Such a target is external-looking and published, but it is ours, so both paths below
     * subtract the locally-owned IRIs first: it must never be rejected, and never snapshotted as a copy
     * of someone else's concept. The detector stays IO-free; the ownership lookup belongs here.
     */
    OwnerChangeSet reconcileNkdLinks(ConceptMetadataEntity owner, Model model) {
        String ownerIri = owner.getConceptIri();
        String graphScheme = owner.getGraphName();
        OwnerChangeSet ownerChangeSet = new OwnerChangeSet();

        List<String> domainRangeTargets =
                nkdLinkDetector.forbiddenDomainRangeTargets(
                        ownerIri, owner.getConceptType(), graphScheme, model);
        List<NkdLinkDetector.LinkTarget> detectedTargets =
                nkdLinkDetector.allowedTargets(ownerIri, owner.getConceptType(), graphScheme, model);

        Set<String> locallyOwned = locallyOwnedAmong(domainRangeTargets, detectedTargets);

        List<String> foreignDomainRangeTargets = domainRangeTargets.stream()
                .filter(iri -> !locallyOwned.contains(iri))
                .toList();
        if (!foreignDomainRangeTargets.isEmpty()) {
            Set<String> publishedForbidden = publishedAmong(foreignDomainRangeTargets);
            if (!publishedForbidden.isEmpty()) {
                throw new OntologyValidationException(
                        "Definiční obor / obor hodnot nesmí odkazovat na publikovaný pojem v NKD: "
                                + publishedForbidden);
            }
        }

        List<NkdLinkDetector.LinkTarget> allowed = detectedTargets.stream()
                .filter(t -> !locallyOwned.contains(t.targetIri()))
                .toList();
        Set<String> currentTargetIris = new HashSet<>();
        allowed.forEach(t -> currentTargetIris.add(t.targetIri()));

        List<NkdConceptSnapshotEntity> existing = nkdSnapshotService.findForConcept(owner.getId());
        if (!existing.isEmpty()) {
            Set<Statement> ownerOutgoing = outgoingStatements(model, ownerIri);
            for (NkdConceptSnapshotEntity snapshot : existing) {
                if (!currentTargetIris.contains(snapshot.getNkdIri())) {
                    nkdSnapshotService.removeSnapshotAndLink(snapshot, ownerOutgoing, ownerChangeSet);
                }
            }
        }

        for (NkdLinkDetector.LinkTarget target : allowed) {
            nkdSnapshotService.createOrRefreshSnapshot(
                    owner, target.targetIri(), target.linkType().value(), ownerChangeSet);
        }
        return ownerChangeSet;
    }

    /** The owner concept's current outgoing statements in {@code model} (for unlink triple removal). */
    Set<Statement> outgoingStatements(Model model, String ownerIri) {
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

    /**
     * Which of the reconcile candidates are concepts we own locally (working copies). One batch lookup
     * over the union of both candidate sets, so the reject path and the snapshot path share a single query.
     */
    Set<String> locallyOwnedAmong(List<String> domainRangeTargets,
                                          List<NkdLinkDetector.LinkTarget> detectedTargets) {
        Set<String> candidates = new HashSet<>(domainRangeTargets);
        detectedTargets.forEach(t -> candidates.add(t.targetIri()));
        if (candidates.isEmpty()) {
            return Set.of();
        }
        return conceptMetadataRepository.findByConceptIriIn(new ArrayList<>(candidates)).stream()
                .map(ConceptMetadataEntity::getConceptIri)
                .collect(Collectors.toSet());
    }

    /** Best-effort batch "which of these IRIs are published in NKD"; empty set on any failure (fail-open). */
    Set<String> publishedAmong(List<String> iris) {
        try {
            return new HashSet<>(nkdSparqlClient.getPublishedResourcesList(iris));
        } catch (Exception e) {
            log.warn("NKD published-check failed during edit reconcile (fail-open, enforcement skipped): {}",
                    e.getMessage());
            return Set.of();
        }
    }
}
