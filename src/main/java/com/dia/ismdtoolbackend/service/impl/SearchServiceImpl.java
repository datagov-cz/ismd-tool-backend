package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.dto.SearchResponseDto;
import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.controller.dto.SourceStatusDto;
import com.dia.ismdtoolbackend.enums.*;
import com.dia.ismdtoolbackend.service.SearchService;
import com.dia.ismdtoolbackend.service.search.SearchProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class SearchServiceImpl implements SearchService {

    private static final long SOURCE_TIMEOUT_MS = 10_000;

    private final SearchProvider nkdSearchProvider;
    private final SearchProvider ismdSearchProvider;

    public SearchServiceImpl(@Qualifier("nkdSearchProvider") SearchProvider nkdSearchProvider,
                             @Qualifier("ismdSearchProvider") SearchProvider ismdSearchProvider) {
        this.nkdSearchProvider = nkdSearchProvider;
        this.ismdSearchProvider = ismdSearchProvider;
    }

    @Override
    public SearchResponseDto search(String query, SearchType type, SearchSource source,
                                    int limit, int offset, String lang,
                                    List<String> ontologyIris, List<RelationType> relationTypes,
                                    SecurityUser user) {
        boolean isAuthenticated = user != null;
        SearchSource effectiveSource = resolveSource(source, isAuthenticated);
        String userId = isAuthenticated ? user.getUserId() : null;

        boolean searchNkd = effectiveSource == SearchSource.NKD || effectiveSource == SearchSource.ALL;
        boolean searchIsmd = effectiveSource == SearchSource.ISMD || effectiveSource == SearchSource.ALL;

        // Dispatch provider calls in parallel, each with its own timeout
        CompletableFuture<SourceSearchResult> nkdFuture = searchNkd
                ? dispatchProviderSearch(nkdSearchProvider, "NKD",
                        query, type, limit, offset, lang, ontologyIris, relationTypes, userId)
                : null;

        CompletableFuture<SourceSearchResult> ismdFuture = searchIsmd
                ? dispatchProviderSearch(ismdSearchProvider, "ISMD",
                        query, type, limit, offset, lang, ontologyIris, relationTypes, userId)
                : null;

        Map<SearchSource, SourceStatusDto> sourceStatuses = new LinkedHashMap<>();
        List<SearchResultDto> allResults = new ArrayList<>();

        if (nkdFuture != null) {
            SourceSearchResult nkdResult = nkdFuture.join();
            allResults.addAll(nkdResult.results());
            sourceStatuses.put(SearchSource.NKD, nkdResult.status());
        }

        if (ismdFuture != null) {
            SourceSearchResult ismdResult = ismdFuture.join();
            allResults.addAll(ismdResult.results());
            sourceStatuses.put(SearchSource.ISMD, ismdResult.status());
        }

        // Dedup by IRI — first occurrence wins (NKD results first when both searched)
        LinkedHashMap<String, SearchResultDto> deduped = new LinkedHashMap<>();
        for (SearchResultDto result : allResults) {
            if (result.getIri() != null) {
                deduped.putIfAbsent(result.getIri(), result);
            }
        }
        List<SearchResultDto> dedupedResults = new ArrayList<>(deduped.values());

        // Mark skipped sources
        if (!sourceStatuses.containsKey(SearchSource.NKD)) {
            sourceStatuses.put(SearchSource.NKD, SourceStatusDto.builder()
                    .status(SearchSourceStatus.SKIPPED)
                    .returnedCount(0)
                    .build());
        }
        if (!sourceStatuses.containsKey(SearchSource.ISMD)) {
            sourceStatuses.put(SearchSource.ISMD, SourceStatusDto.builder()
                    .status(SearchSourceStatus.SKIPPED)
                    .returnedCount(0)
                    .build());
        }

        return SearchResponseDto.builder()
                .results(dedupedResults)
                .returnedCount(dedupedResults.size())
                .limit(limit)
                .offset(offset)
                .sourceStatuses(sourceStatuses)
                .build();
    }

    private CompletableFuture<SourceSearchResult> dispatchProviderSearch(
            SearchProvider provider, String providerName,
            String query, SearchType type, int limit, int offset,
            String lang, List<String> ontologyIris,
            List<RelationType> relationTypes, String userId) {

        return CompletableFuture.supplyAsync(() ->
                        provider.search(query, type, limit, offset, lang,
                                ontologyIris, relationTypes, userId))
                .orTimeout(SOURCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .handle((result, ex) -> {
                    if (ex == null) {
                        return new SourceSearchResult(
                                result.results(),
                                SourceStatusDto.builder()
                                        .status(SearchSourceStatus.OK)
                                        .returnedCount(result.results().size())
                                        .totalCount(result.totalCount())
                                        .build());
                    }

                    Throwable cause = ex instanceof java.util.concurrent.CompletionException
                            ? ex.getCause() : ex;

                    if (cause instanceof TimeoutException) {
                        log.warn("{} search timed out after {}ms", providerName, SOURCE_TIMEOUT_MS);
                        return new SourceSearchResult(
                                List.of(),
                                SourceStatusDto.builder()
                                        .status(SearchSourceStatus.TIMEOUT)
                                        .returnedCount(0)
                                        .message(providerName + " search timed out")
                                        .build());
                    }

                    log.error("{} search failed: {}", providerName, cause.getMessage(), cause);
                    return new SourceSearchResult(
                            List.of(),
                            SourceStatusDto.builder()
                                    .status(SearchSourceStatus.ERROR)
                                    .returnedCount(0)
                                    .message(providerName + " search failed: " + cause.getMessage())
                                    .build());
                });
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

    private record SourceSearchResult(List<SearchResultDto> results, SourceStatusDto status) {
    }
}
