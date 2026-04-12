package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.controller.dto.SearchResponseDto;
import com.dia.ismdtoolbackend.enums.RelationType;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.enums.SearchType;
import com.dia.ismdtoolbackend.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @GetMapping
    public ResponseEntity<ApiResponseDto<SearchResponseDto>> search(
            @RequestParam String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "cs") String lang,
            @RequestParam(required = false) List<String> ontologyIri,
            @RequestParam(required = false) List<String> relationTypes,
            @AuthenticationPrincipal SecurityUser securityUser) {

        // Validate query
        if (q == null || q.trim().length() < 2) {
            throw new IllegalArgumentException("Search query must be at least 2 characters long");
        }

        // Validate limit
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Limit must be between 1 and 100");
        }

        // Validate offset
        if (offset < 0) {
            throw new IllegalArgumentException("Offset must be non-negative");
        }

        // Parse enums
        SearchType searchType = type != null ? SearchType.fromString(type) : null;
        SearchSource searchSource = source != null ? SearchSource.fromString(source) : null;
        List<RelationType> parsedRelationTypes = relationTypes != null
                ? relationTypes.stream().map(RelationType::fromString).toList()
                : null;

        log.info("Search request: q='{}', type={}, source={}, limit={}, offset={}, lang={}, user={}",
                q.trim(), searchType, searchSource, limit, offset, lang,
                securityUser != null ? securityUser.getUserId() : "anonymous");

        SearchResponseDto response = searchService.search(
                q.trim(), searchType, searchSource, limit, offset, lang,
                ontologyIri, parsedRelationTypes, securityUser);

        return ResponseEntity.ok(ApiResponseDto.success(response, "Search completed successfully"));
    }
}
