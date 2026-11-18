package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
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
import com.dia.ismdtoolbackend.service.snapshot.NkdLookup;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Optional;
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
     * of someone else's concept. Only the owner's graph counts as local: a concept of another ISMD
     * vocabulary that NKD publishes is an NKD concept like any other. The detector stays IO-free; the
     * ownership lookup belongs here.
     */
    OwnerChangeSet reconcileNkdLinks(ConceptMetadataEntity owner, Model model) {
        return reconcileNkdLinks(owner, model, live());
    }

    /** NKD asked as the reconcile goes, for callers that cannot fetch ahead. */
    NkdLookup live() {
        return NkdLookup.live(nkdSparqlClient);
    }

    /** As above, reading NKD only through {@code nkd}. */
    OwnerChangeSet reconcileNkdLinks(ConceptMetadataEntity owner, Model model, NkdLookup nkd) {
        String ownerIri = owner.getConceptIri();
        String graphScheme = owner.getGraphName();
        OwnerChangeSet ownerChangeSet = new OwnerChangeSet();

        List<String> domainRangeTargets =
                nkdLinkDetector.forbiddenDomainRangeTargets(
                        ownerIri, owner.getConceptType(), graphScheme, model);
        List<NkdLinkDetector.LinkTarget> detectedTargets =
                nkdLinkDetector.allowedTargets(ownerIri, owner.getConceptType(), graphScheme, model);

        Set<String> locallyOwned = locallyOwnedAmong(graphScheme, domainRangeTargets, detectedTargets);

        requireNonePublished(ownerIri, domainRangeTargets.stream()
                .filter(iri -> !locallyOwned.contains(iri))
                .toList(), nkd);

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
                    owner, target.targetIri(), target.linkType().value(), ownerChangeSet, nkd);
        }
        return ownerChangeSet;
    }

    /**
     * The create half of the rule: rejects (400) a new concept whose {@code rdfs:domain} / {@code rdfs:range}
     * is a concept NKD publishes. Working copies in {@code graphName} are exempt; a create has no prior link
     * state, so there is nothing to snapshot or remove here (the warmer takes the copies).
     */
    void rejectPublishedDomainRange(String conceptIri, ConceptType conceptType, String graphName, Model model,
                                    NkdLookup nkd) {
        rejectPublishedDomainRange(graphName, Map.of(conceptIri, conceptType), model, nkd);
    }

    /**
     * As above for several new concepts of one graph, in one NKD question; the 400 names every offending
     * concept with its published targets.
     */
    void rejectPublishedDomainRange(String graphName, Map<String, ConceptType> concepts, Model model, NkdLookup nkd) {
        Map<String, List<String>> targets = new LinkedHashMap<>();
        concepts.forEach((iri, type) -> {
            List<String> found = nkdLinkDetector.forbiddenDomainRangeTargets(iri, type, graphName, model);
            if (!found.isEmpty()) {
                targets.put(iri, found);
            }
        });
        Set<String> candidates = new LinkedHashSet<>();
        targets.values().forEach(candidates::addAll);
        candidates.removeAll(ownedIn(graphName, candidates));
        if (candidates.isEmpty()) {
            return;
        }
        Optional<Set<String>> answer = nkd.publishedAmong(candidates);
        if (answer.isEmpty()) {
            log.warn("NKD did not answer; domain/range check skipped for concept(s) {} (targets {})",
                    targets.keySet(), candidates);
            return;
        }
        Map<String, List<String>> offending = new LinkedHashMap<>();
        targets.forEach((iri, found) -> {
            List<String> published = found.stream().filter(answer.get()::contains).toList();
            if (!published.isEmpty()) {
                offending.put(iri, published);
            }
        });
        if (!offending.isEmpty()) {
            throw new OntologyValidationException(
                    "Definiční obor / obor hodnot nesmí odkazovat na publikovaný pojem v NKD: " + offending);
        }
    }

    /** Throws when NKD publishes any of {@code foreignTargets}; NKD not answering skips the check with a warning. */
    private static void requireNonePublished(String conceptIri, List<String> foreignTargets, NkdLookup nkd) {
        if (foreignTargets.isEmpty()) {
            return;
        }
        Optional<Set<String>> answer = nkd.publishedAmong(foreignTargets);
        if (answer.isEmpty()) {
            log.warn("NKD did not answer; domain/range check skipped for concept {} (targets {})",
                    conceptIri, foreignTargets);
            return;
        }
        if (!answer.get().isEmpty()) {
            throw new OntologyValidationException(
                    "Definiční obor / obor hodnot nesmí odkazovat na publikovaný pojem v NKD: " + answer.get());
        }
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
     * Which of the reconcile candidates are working copies: concepts in the owner's graph {@code graphName}.
     * One batch lookup over the union of both candidate sets, so the reject path and the snapshot path share
     * a single query.
     */
    Set<String> locallyOwnedAmong(String graphName, List<String> domainRangeTargets,
                                  List<NkdLinkDetector.LinkTarget> detectedTargets) {
        Set<String> candidates = new HashSet<>(domainRangeTargets);
        detectedTargets.forEach(t -> candidates.add(t.targetIri()));
        return ownedIn(graphName, candidates);
    }

    private Set<String> ownedIn(String graphName, Collection<String> iris) {
        if (graphName == null || iris.isEmpty()) {
            return Set.of();
        }
        return conceptMetadataRepository.findByGraphNameAndConceptIriIn(graphName, List.copyOf(iris)).stream()
                .map(ConceptMetadataEntity::getConceptIri)
                .collect(Collectors.toSet());
    }

    /**
     * What a reconcile of {@code model}'s links would ask NKD, fetched now, outside the edit's transaction:
     * the external IRIs the payload names, minus working copies in the owner's graph.
     */
    NkdLookup prefetch(String graphScheme, Collection<String> referencedIris) {
        return NkdLookup.prefetch(nkdSparqlClient, nkdCandidates(graphScheme, referencedIris));
    }

    /** As {@link #prefetch}, asking only which IRIs are published (a create takes no copies). */
    NkdLookup prefetchPublished(String graphScheme, Collection<String> referencedIris) {
        return NkdLookup.prefetchPublished(nkdSparqlClient, nkdCandidates(graphScheme, referencedIris));
    }

    /**
     * The referenced IRIs NKD can be asked about: outside {@code graphScheme}, safe to put in a query, and not
     * working copies in that graph. Values that are not IRIs are left for the input validation to reject.
     */
    private List<String> nkdCandidates(String graphScheme, Collection<String> referencedIris) {
        List<String> external = referencedIris.stream()
                .filter(iri -> iri != null && !iri.isBlank())
                .map(String::trim)
                .filter(iri -> graphScheme == null || !iri.startsWith(graphScheme))
                .filter(SparqlIriValidator::isSafeHttpIri)
                .distinct()
                .toList();
        Set<String> owned = ownedIn(graphScheme, external);
        return external.stream().filter(iri -> !owned.contains(iri)).toList();
    }
}
