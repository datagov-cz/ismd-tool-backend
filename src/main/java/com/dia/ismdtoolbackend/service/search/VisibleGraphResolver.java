package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** Resolves which graphs a search may read, and narrows them by the request's ontology filter. */
@Component
@RequiredArgsConstructor
class VisibleGraphResolver {

    private final OntologyMetadataRepository ontologyMetadataRepository;

    /** The graph filter for one request: the caller's visible graphs narrowed by {@code ontologyIris}. */
    GraphFilter graphFilterFor(List<String> ontologyIris, String userId, boolean isAdmin, Boolean publishedFilter) {
        return resolveGraphFilter(ontologyIris, visibleGraphsFor(userId, isAdmin, publishedFilter));
    }

    /**
     * Visible-graph set used to scope Fuseki text queries so every hit lives
     * inside an ontology the caller is permitted to see. Unpublished-only
     * searches narrow further: admin sees all unpublished, regular user sees
     * only their own.
     */
    List<String> visibleGraphsFor(String userId, boolean isAdmin, Boolean publishedFilter) {
        boolean unpublishedOnly = Boolean.FALSE.equals(publishedFilter);
        return unpublishedOnly
                ? getUnpublishedVisibleGraphNames(userId, isAdmin)
                : getVisibleGraphNames();
    }

    /**
     * Derives the three graph-name views needed downstream:
     * — {@code hasGraphFilter}: whether the caller restricted to specific ontologies
     * — {@code graphNames}: PG-side {@code IN (...)} list (empty when no filter)
     * — {@code searchGraphs}: Fuseki-side scoped graph list, intersected with what
     *   the caller is allowed to see (so an arbitrary IRI in the request can't
     *   widen visibility)
     */
    static GraphFilter resolveGraphFilter(List<String> ontologyIris, List<String> visibleGraphNames) {
        boolean hasGraphFilter = ontologyIris != null && !ontologyIris.isEmpty();
        List<String> graphNames = hasGraphFilter ? ontologyIris : List.of();
        List<String> searchGraphs = hasGraphFilter
                ? ontologyIris.stream().filter(visibleGraphNames::contains).toList()
                : visibleGraphNames;
        return new GraphFilter(hasGraphFilter, graphNames, searchGraphs);
    }

    /**
     * Graph names visible on the default (no-source) search pass: every ontology,
     * published AND unpublished drafts of all users. Scopes Fuseki text queries so a
     * concept hit is transitively inside an ontology the caller may see — which, on
     * this pass, is all of them (anonymous callers never reach ISMD search). The
     * {@code userId} parameter is retained for signature symmetry with the
     * unpublished-only path but is no longer needed to narrow visibility.
     */
    List<String> getVisibleGraphNames() {
        return ontologyMetadataRepository.findAll().stream()
                .map(OntologyMetadataEntity::getGraphName)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * Graph names visible to the caller on the {@code UNPUBLISHED} ("rozpracovaný")
     * pass — every local ontology, matching the PG row filter so the Fuseki label
     * search and the PG slug search agree on scope. "Rozpracovaný" means local rather
     * than draft-flagged: an uploaded working copy is {@code is_published = true}
     * throughout until a concept is edited, so a publish-state test here would hide
     * whole vocabularies from the filter.
     * <p>
     * Every authenticated caller sees every such graph regardless of ownership; the
     * {@code userId}/{@code isAdmin} short-circuit only mirrors the defense-in-depth
     * guard that keeps an anonymous, non-admin caller from seeing any unpublished
     * rows (they are already rejected upstream at
     * {@code SearchServiceImpl.resolveSource}).
     */
    List<String> getUnpublishedVisibleGraphNames(String userId, boolean isAdmin) {
        if (!isAdmin && userId == null) {
            return List.of();
        }
        return ontologyMetadataRepository.findAllGraphNames().stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
