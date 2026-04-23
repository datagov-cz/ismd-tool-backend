package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.enums.RelationType;
import com.dia.ismdtoolbackend.enums.SearchSourceStatus;
import com.dia.ismdtoolbackend.enums.SearchType;

import java.util.List;

public interface SearchProvider {

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

    record SearchProviderResult(List<SearchResultDto> results, int totalCount, SearchSourceStatus status, String statusMessage) {
        public SearchProviderResult(List<SearchResultDto> results, int totalCount) {
            this(results, totalCount, SearchSourceStatus.OK, null);
        }
    }
}
