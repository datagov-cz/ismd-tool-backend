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
 * Diagram rows for ISMD search, mapped to DTOs.
 *
 * <p>Its own bean rather than a method on {@link IsmdSearchProvider}: a private method there would be
 * self-invoked and bypass the transaction proxy, and the transaction must not span the provider's whole
 * {@code search}, which also calls Fuseki and would hold a pooled connection across that HTTP call.
 *
 * <p>The queries project the ontology columns rather than returning entities, so no LAZY association is
 * traversed during mapping and neither the per-row SELECT nor a {@code LazyInitializationException} is
 * reachable here.
 */
@Component
@RequiredArgsConstructor
public class DiagramSearchLookup {

    private final DiagramRepository diagramRepository;

    /**
     * One page of matching diagrams, already mapped. {@code publishedFilter} mirrors the ontology branch:
     * {@code FALSE} narrows to unpublished ontologies' diagrams, {@code null} returns them regardless of
     * publish state.
     *
     * <p>The page is cut in SQL, so the caller must not slice again — passing the whole corpus through
     * Java to keep 20 rows is what this signature exists to prevent.
     *
     * <p>No ownership filter, matching {@code OntologyMetadataRepository.searchByText}: any authenticated
     * caller sees every slovník, drafts included, and anonymous callers are forced to NKD before reaching
     * here. A diagram is no more visible than its ontology, though it does surface a user-authored name
     * where the ontology row surfaces only a slug.
     */
    @Transactional(readOnly = true)
    public List<SearchResultDto> search(String query, Boolean publishedFilter, int limit, int offset) {
        return diagramRepository
                .searchByOntologyText(query, Boolean.FALSE.equals(publishedFilter), limit, offset)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public long count(String query, Boolean publishedFilter) {
        return diagramRepository.countSearchByOntologyText(query, Boolean.FALSE.equals(publishedFilter));
    }

    /**
     * Synthetic IRI ({@code <graphName>#diagram-<id>}) so a diagram row dedup-collides neither with its
     * ontology's own ONTOLOGY row nor with the ontology's other diagrams.
     *
     * <p>{@code label} is the diagram's own name, while {@code slug} stays the ontology's and pairs with
     * {@code diagramId} to route {@code /api/diagram/{slug}/{diagramId}/detail}. {@code isPublished} is the
     * ontology's, a diagram having no publish state of its own.
     */
    private SearchResultDto toDto(DiagramRepository.DiagramSearchRow d) {
        String graphName = d.getGraphName();
        String slug = d.getSlug();
        return SearchResultDto.builder()
                .id(d.getDiagramId())
                .diagramId(d.getDiagramId())
                .iri(graphName != null ? graphName + "#diagram-" + d.getDiagramId() : "diagram:" + d.getDiagramId())
                .slug(slug)
                .label(d.getName() != null ? d.getName() : slug)
                .type(SearchType.DIAGRAM)
                .source(SearchSource.ISMD)
                .ontologyIri(graphName)
                .isPublished(d.getIsPublished())
                .lastModified(d.getUpdatedAt() != null ? d.getUpdatedAt().toString() : null)
                .build();
    }

}