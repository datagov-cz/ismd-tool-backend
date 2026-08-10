package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.enums.SearchType;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Diagram rows for ISMD search, mapped to DTOs inside an open persistence session.
 *
 * <p>Its own bean, not a method on {@link IsmdSearchProvider}, for two reasons. A private method there
 * would be self-invoked and bypass the transaction proxy entirely. And the transaction must NOT span
 * the provider's whole {@code search} — that method also calls Fuseki (10s timeout) and would hold a
 * pooled DB connection across an external HTTP call.
 *
 * <p>The session matters because {@code searchByOntologyText} is a native query (no fetch join
 * possible) and {@link DiagramEntity#getOntologyMetadata()} is {@code LAZY} — mapping outside a
 * session throws {@code LazyInitializationException}, which aborts the whole ISMD provider and
 * silently discards its ontology and concept results too.
 */
@Component
@RequiredArgsConstructor
public class DiagramSearchLookup {

    private final DiagramRepository diagramRepository;

    /**
     * Diagrams whose ontology slug matches, already mapped — no lazy proxy escapes.
     *
     * <p>{@code REQUIRED}, not {@code REQUIRES_NEW}: the search provider runs without an ambient
     * transaction, so this opens one either way, and joining an existing transaction (rather than
     * suspending it for a second pooled connection) is the cheaper behaviour if a caller ever has one.
     */
    @Transactional(readOnly = true)
    public List<SearchResultDto> search(String query) {
        return diagramRepository.searchByOntologyText(query).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public long count(String query) {
        return diagramRepository.countSearchByOntologyText(query);
    }

    /**
     * Synthetic IRI ({@code <graphName>#diagram}) so a diagram row never dedup-collides with its
     * ontology's own ONTOLOGY row on a {@code type=null} pass.
     */
    private SearchResultDto toDto(DiagramEntity d) {
        String graphName = d.getOntologyMetadata().getGraphName();
        String slug = d.getOntologyMetadata().getSlug();
        return SearchResultDto.builder()
                .id(d.getId())
                .iri(graphName != null ? graphName + "#diagram" : "diagram:" + d.getId())
                .slug(slug)
                .label(slug)
                .type(SearchType.DIAGRAM)
                .source(SearchSource.ISMD)
                .ontologyIri(graphName)
                .lastModified(d.getUpdatedAt() != null ? d.getUpdatedAt().toString() : null)
                .build();
    }
}