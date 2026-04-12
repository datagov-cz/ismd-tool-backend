package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.dto.SearchResponseDto;
import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.controller.dto.SourceStatusDto;
import com.dia.ismdtoolbackend.enums.*;
import com.dia.ismdtoolbackend.service.SearchService;
import com.dia.ismdtoolbackend.service.search.NkdSearchProvider;
import com.dia.ismdtoolbackend.service.search.SearchProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    private static final long SOURCE_TIMEOUT_MS = 10_000;

    private final NkdSearchProvider nkdSearchProvider;

    @Override
    public SearchResponseDto search(String query, SearchType type, SearchSource source,
                                    int limit, int offset, String lang,
                                    List<String> ontologyIris, List<RelationType> relationTypes,
                                    SecurityUser user) {
        boolean isAuthenticated = user != null;
        SearchSource effectiveSource = resolveSource(source, isAuthenticated);
        String userId = isAuthenticated ? user.getUserId() : null;

        Map<SearchSource, SourceStatusDto> sourceStatuses = new LinkedHashMap<>();
        List<SearchResultDto> allResults = new ArrayList<>();

        // NKD search
        if (effectiveSource == SearchSource.NKD || effectiveSource == SearchSource.ALL) {
            SourceStatusDto nkdStatus = executeNkdSearch(query, type, limit, offset, lang,
                    ontologyIris, relationTypes, userId, allResults);
            sourceStatuses.put(SearchSource.NKD, nkdStatus);
        }

        // ISMD search (Phase 3+)
        if (effectiveSource == SearchSource.ISMD || effectiveSource == SearchSource.ALL) {
            sourceStatuses.put(SearchSource.ISMD, SourceStatusDto.builder()
                    .status(SearchSourceStatus.SKIPPED)
                    .returnedCount(0)
                    .message("ISMD search not yet implemented")
                    .build());
        }

        // Mark skipped sources
        if (effectiveSource == SearchSource.NKD && !sourceStatuses.containsKey(SearchSource.ISMD)) {
            sourceStatuses.put(SearchSource.ISMD, SourceStatusDto.builder()
                    .status(SearchSourceStatus.SKIPPED)
                    .returnedCount(0)
                    .build());
        }
        if (effectiveSource == SearchSource.ISMD && !sourceStatuses.containsKey(SearchSource.NKD)) {
            sourceStatuses.put(SearchSource.NKD, SourceStatusDto.builder()
                    .status(SearchSourceStatus.SKIPPED)
                    .returnedCount(0)
                    .build());
        }

        return SearchResponseDto.builder()
                .results(allResults)
                .returnedCount(allResults.size())
                .limit(limit)
                .offset(offset)
                .sourceStatuses(sourceStatuses)
                .build();
    }

    private SourceStatusDto executeNkdSearch(String query, SearchType type, int limit, int offset,
                                              String lang, List<String> ontologyIris,
                                              List<RelationType> relationTypes, String userId,
                                              List<SearchResultDto> allResults) {
        try {
            CompletableFuture<SearchProvider.SearchProviderResult> future =
                    CompletableFuture.supplyAsync(() ->
                            nkdSearchProvider.search(query, type, limit, offset, lang,
                                    ontologyIris, relationTypes, userId));

            SearchProvider.SearchProviderResult result = future.get(SOURCE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            allResults.addAll(result.results());

            return SourceStatusDto.builder()
                    .status(SearchSourceStatus.OK)
                    .returnedCount(result.results().size())
                    .totalCount(result.totalCount())
                    .build();

        } catch (TimeoutException e) {
            log.warn("NKD search timed out after {}ms", SOURCE_TIMEOUT_MS);
            return SourceStatusDto.builder()
                    .status(SearchSourceStatus.TIMEOUT)
                    .returnedCount(0)
                    .message("NKD search timed out")
                    .build();

        } catch (Exception e) {
            log.error("NKD search failed: {}", e.getMessage(), e);
            return SourceStatusDto.builder()
                    .status(SearchSourceStatus.ERROR)
                    .returnedCount(0)
                    .message("NKD search failed: " + e.getMessage())
                    .build();
        }
    }

    private SearchSource resolveSource(SearchSource requested, boolean isAuthenticated) {
        if (!isAuthenticated) {
            if (requested == SearchSource.ISMD || requested == SearchSource.ALL) {
                throw new SecurityException("Authentication required to search ISMD resources");
            }
            return SearchSource.NKD;
        }
        return requested != null ? requested : SearchSource.ALL;
    }
}
