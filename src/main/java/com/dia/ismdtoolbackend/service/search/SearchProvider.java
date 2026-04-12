package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.enums.RelationType;
import com.dia.ismdtoolbackend.enums.SearchType;

import java.util.List;

public interface SearchProvider {

    SearchProviderResult search(String query, SearchType type, int limit, int offset,
                                String lang, List<String> ontologyIris,
                                List<RelationType> relationTypes, String userId);

    record SearchProviderResult(List<SearchResultDto> results, int totalCount) {
    }
}
