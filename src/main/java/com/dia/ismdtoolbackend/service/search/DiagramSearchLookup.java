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
 * <p>Its own bean rather than a method on {@link IsmdSearchProvider}: a private method there would be
 * self-invoked and bypass the transaction proxy, and the transaction must not span the provider's whole
 * {@code search}, which also calls Fuseki and would hold a pooled connection across that HTTP call.
 *
 * <p>The session is required because {@code searchByOntologyText} is native and
 * {@link DiagramEntity#getOntologyMetadata()} is {@code LAZY}, so mapping outside one throws
 * {@code LazyInitializationException} and aborts the whole ISMD provider.
 */
@Component
@RequiredArgsConstructor
public class DiagramSearchLookup {

    private final DiagramRepository diagramRepository;

    /**
     * Matching diagrams, already mapped, so no lazy proxy escapes. {@code publishedFilter} mirrors the
     * ontology branch: {@code FALSE} narrows to unpublished ontologies' diagrams, {@code null} returns them
     * regardless of publish state.
     *
     * <p>No ownership filter, matching {@code OntologyMetadataRepository.searchByText}: any authenticated
     * caller sees every slovník, drafts included, and anonymous callers are forced to NKD before reaching
     * here. A diagram is no more visible than its ontology, though it does surface a user-authored name
     * where the ontology row surfaces only a slug.
     */
    @Transactional(readOnly = true)
    public List<SearchResultDto> search(String query, Boolean publishedFilter) {
        List<DiagramEntity> rows = Boolean.FALSE.equals(publishedFilter)
                ? diagramRepository.searchByOntologyTextUnpublished(query)
                : diagramRepository.searchByOntologyText(query);
        return rows.stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public long count(String query, Boolean publishedFilter) {
        return Boolean.FALSE.equals(publishedFilter)
                ? diagramRepository.countSearchByOntologyTextUnpublished(query)
                : diagramRepository.countSearchByOntologyText(query);
    }

    /**
     * Synthetic IRI ({@code <graphName>#diagram-<id>}) so a diagram row dedup-collides neither with its
     * ontology's own ONTOLOGY row nor with the ontology's other diagrams.
     *
     * <p>{@code label} is the diagram's own name, while {@code slug} stays the ontology's and pairs with
     * {@code diagramId} to route {@code /api/diagram/{slug}/{diagramId}/detail}. {@code isPublished} is the
     * ontology's, a diagram having no publish state of its own.
     */
    private SearchResultDto toDto(DiagramEntity d) {
        String graphName = d.getOntologyMetadata().getGraphName();
        String slug = d.getOntologyMetadata().getSlug();
        return SearchResultDto.builder()
                .id(d.getId())
                .diagramId(d.getId())
                .iri(graphName != null ? graphName + "#diagram-" + d.getId() : "diagram:" + d.getId())
                .slug(slug)
                .label(d.getName() != null ? d.getName() : slug)
                .type(SearchType.DIAGRAM)
                .source(SearchSource.ISMD)
                .ontologyIri(graphName)
                .isPublished(d.getOntologyMetadata().getIsPublished())
                .lastModified(d.getUpdatedAt() != null ? d.getUpdatedAt().toString() : null)
                .build();
    }

}