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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class SearchServiceImpl implements SearchService {

    private final long sourceTimeoutMs;
    private final SearchProvider nkdSearchProvider;
    private final SearchProvider ismdSearchProvider;
    private final Executor searchExecutor;

    public SearchServiceImpl(@Qualifier("nkdSearchProvider") SearchProvider nkdSearchProvider,
                             @Qualifier("ismdSearchProvider") SearchProvider ismdSearchProvider,
                             @Qualifier("searchExecutor") Executor searchExecutor,
                             @Value("${search.source-timeout-ms:10000}") long sourceTimeoutMs) {
        this.nkdSearchProvider = nkdSearchProvider;
        this.ismdSearchProvider = ismdSearchProvider;
        this.searchExecutor = searchExecutor;
        this.sourceTimeoutMs = sourceTimeoutMs;
    }

    @Override
    public SearchResponseDto search(String query, SearchType type, SearchSource source,
                                    int limit, int offset, String lang,
                                    List<String> ontologyIris, List<RelationType> relationTypes,
                                    SecurityUser user) {
        boolean isAuthenticated = user != null;
        SearchSource effectiveSource = resolveSource(source, isAuthenticated);
        String userId = isAuthenticated ? user.getUserId() : null;
        boolean isAdmin = isAuthenticated && user.isAdmin();

        boolean searchNkd = effectiveSource == SearchSource.NKD || effectiveSource == SearchSource.ALL;
        // UNPUBLISHED is an ISMD dispatch with the is_published=false filter applied.
        boolean searchIsmd = effectiveSource == SearchSource.ISMD
                || effectiveSource == SearchSource.ALL
                || effectiveSource == SearchSource.UNPUBLISHED;
        Boolean publishedFilter = effectiveSource == SearchSource.UNPUBLISHED ? Boolean.FALSE : null;
        // The logical "source slot" this ISMD call fills in the response — UNPUBLISHED
        // is reported under its own key so a caller can tell whether results came from
        // a published or unpublished search pass.
        SearchSource ismdReportedAs = effectiveSource == SearchSource.UNPUBLISHED
                ? SearchSource.UNPUBLISHED
                : SearchSource.ISMD;

        // Dispatch provider calls in parallel, each with its own timeout
        CompletableFuture<SourceSearchResult> nkdFuture = searchNkd
                ? dispatchProviderSearch(nkdSearchProvider, "NKD",
                        query, type, limit, offset, lang, ontologyIris, relationTypes,
                        userId, isAdmin, null)
                : null;

        // providerName is for logs/telemetry — always "ISMD" because the same
        // provider answers both ISMD and UNPUBLISHED requests. The response slot
        // (ismdReportedAs) is a separate concept used only for sourceStatuses keying.
        CompletableFuture<SourceSearchResult> ismdFuture = searchIsmd
                ? dispatchProviderSearch(ismdSearchProvider, "ISMD",
                        query, type, limit, offset, lang, ontologyIris, relationTypes,
                        userId, isAdmin, publishedFilter)
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
            sourceStatuses.put(ismdReportedAs, ismdResult.status());
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
        if (!sourceStatuses.containsKey(ismdReportedAs)) {
            sourceStatuses.put(ismdReportedAs, SourceStatusDto.builder()
                    .status(SearchSourceStatus.SKIPPED)
                    .returnedCount(0)
                    .build());
        }

        // Roll up per-source totals into top-level fields. A source that returned
        // null (couldn't compute a count — partial degradation) is not summed,
        // but a source that returned 0 is. If every contributing source is null,
        // the rollup itself stays null so the FE can distinguish "no data" from
        // "actually zero".
        Integer totalOntologies = sumNullable(sourceStatuses.values(), SourceStatusDto::getTotalOntologies);
        Integer totalConcepts = sumNullable(sourceStatuses.values(), SourceStatusDto::getTotalConcepts);

        return SearchResponseDto.builder()
                .results(dedupedResults)
                .returnedCount(dedupedResults.size())
                .limit(limit)
                .offset(offset)
                .sourceStatuses(sourceStatuses)
                .totalOntologies(totalOntologies)
                .totalConcepts(totalConcepts)
                .build();
    }

    private static Integer sumNullable(java.util.Collection<SourceStatusDto> statuses,
                                        java.util.function.Function<SourceStatusDto, Integer> extractor) {
        int sum = 0;
        boolean anyPresent = false;
        for (SourceStatusDto status : statuses) {
            Integer v = extractor.apply(status);
            if (v != null) {
                sum += v;
                anyPresent = true;
            }
        }
        return anyPresent ? sum : null;
    }

    private CompletableFuture<SourceSearchResult> dispatchProviderSearch(
            SearchProvider provider, String providerName,
            String query, SearchType type, int limit, int offset,
            String lang, List<String> ontologyIris,
            List<RelationType> relationTypes, String userId,
            boolean isAdmin, Boolean publishedFilter) {

        return CompletableFuture.supplyAsync(() ->
                        provider.search(query, type, limit, offset, lang,
                                ontologyIris, relationTypes, userId, isAdmin, publishedFilter), searchExecutor)
                .orTimeout(sourceTimeoutMs, TimeUnit.MILLISECONDS)
                .handle((result, ex) -> {
                    if (ex == null) {
                        return new SourceSearchResult(
                                result.results(),
                                SourceStatusDto.builder()
                                        .status(result.status())
                                        .returnedCount(result.results().size())
                                        .totalOntologies(result.totalOntologies())
                                        .totalConcepts(result.totalConcepts())
                                        .message(result.statusMessage())
                                        .build());
                    }

                    Throwable cause = ex instanceof java.util.concurrent.CompletionException
                            ? ex.getCause() : ex;

                    if (cause instanceof TimeoutException) {
                        log.warn("{} search timed out after {}ms", providerName, sourceTimeoutMs);
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
            if (requested == SearchSource.ISMD
                    || requested == SearchSource.ALL
                    || requested == SearchSource.UNPUBLISHED) {
                throw new SecurityException("Authentication required to search ISMD resources");
            }
            return SearchSource.NKD;
        }
        return requested != null ? requested : SearchSource.ALL;
    }

    private record SourceSearchResult(List<SearchResultDto> results, SourceStatusDto status) {
    }
}
