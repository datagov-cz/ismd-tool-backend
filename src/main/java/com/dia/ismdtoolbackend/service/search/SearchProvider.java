package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.enums.RelationType;
import com.dia.ismdtoolbackend.enums.SearchSourceStatus;
import com.dia.ismdtoolbackend.enums.SearchType;

import java.util.List;
import java.util.function.Supplier;

public interface SearchProvider {

    /**
     * Conditionally invoke a count loader. If {@code matches} is false, returns
     * {@code 0} (the type-correct sentinel meaning "we deliberately didn't count").
     * If true, returns whatever the loader returns — including {@code null} when
     * the loader couldn't produce a count (e.g. remote failure).
     * <p>
     * Exists to avoid the "ternary auto-unbox" footgun: writing
     * {@code matches ? loader.get() : 0} compiles to {@code int} and NPEs when
     * the loader returns {@code null}.
     */
    static Integer countIfMatches(boolean matches, Supplier<Integer> loader) {
        return matches ? loader.get() : Integer.valueOf(0);
    }

    /**
     * @param publishedFilter  {@code null} = don't filter; {@code TRUE} = only
     *                         {@code is_published = true}; {@code FALSE} = only
     *                         {@code is_published = false}. Only meaningful for
     *                         ISMD-like providers with a local publish flag.
     * @param isAdmin          whether the current user has the admin role. Used
     *                         by providers that broaden visibility for admins
     *                         (e.g. UNPUBLISHED source sees all unpublished for
     *                         admin, owner-only otherwise).
     */
    SearchProviderResult search(String query, SearchType type, int limit, int offset,
                                String lang, List<String> ontologyIris,
                                List<RelationType> relationTypes, String userId,
                                boolean isAdmin, Boolean publishedFilter);

    /**
     * @param totalCount       total matches (ontologies + concepts + diagrams) for this
     *                         query in this source, across all pages
     * @param totalOntologies  ontology-only subtotal; {@code null} when the source
     *                         couldn't produce a count (e.g. partial degradation)
     * @param totalConcepts    concept-only subtotal; {@code null} when unknown
     * @param totalDiagrams    diagram-only subtotal; {@code null} when unknown. Only
     *                         ISMD contributes; NKD always passes {@code null}.
     */
    record SearchProviderResult(List<SearchResultDto> results, int totalCount,
                                Integer totalOntologies, Integer totalConcepts,
                                Integer totalDiagrams,
                                SearchSourceStatus status, String statusMessage) {
        public SearchProviderResult(List<SearchResultDto> results, int totalCount) {
            this(results, totalCount, null, null, null, SearchSourceStatus.OK, null);
        }

        public SearchProviderResult(List<SearchResultDto> results, int totalCount,
                                     SearchSourceStatus status, String statusMessage) {
            this(results, totalCount, null, null, null, status, statusMessage);
        }

        public SearchProviderResult(List<SearchResultDto> results, int totalCount,
                                     Integer totalOntologies, Integer totalConcepts) {
            this(results, totalCount, totalOntologies, totalConcepts, null, SearchSourceStatus.OK, null);
        }

        public SearchProviderResult(List<SearchResultDto> results, int totalCount,
                                     Integer totalOntologies, Integer totalConcepts,
                                     Integer totalDiagrams) {
            this(results, totalCount, totalOntologies, totalConcepts, totalDiagrams,
                    SearchSourceStatus.OK, null);
        }
    }
}
