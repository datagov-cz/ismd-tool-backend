package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.controller.dto.SearchResponseDto;
import com.dia.ismdtoolbackend.enums.RelationType;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.enums.SearchType;
import com.dia.ismdtoolbackend.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
public class SearchController {

    private final SearchService searchService;
    private final int minQueryLength;
    private final int maxLimit;

    public SearchController(SearchService searchService,
                            @Value("${search.min-query-length:2}") int minQueryLength,
                            @Value("${search.max-limit:100}") int maxLimit) {
        this.searchService = searchService;
        this.minQueryLength = minQueryLength;
        this.maxLimit = maxLimit;
    }

    @Operation(
            summary = "Vyhledávání slovníků a pojmů",
            description = "Vyhledává slovníky a pojmy napříč zdroji NKD a ISMD. " +
                    "Anonymní uživatelé mohou vyhledávat pouze v NKD, přihlášení uživatelé oba zdroje."
    )
    @GetMapping
    public ResponseEntity<ApiResponseDto<SearchResponseDto>> search(
            @Parameter(description = "Vyhledávací dotaz (min. 2 znaky)", required = true)
            @RequestParam String q,
            @Parameter(description = "Typ výsledku: ONTOLOGY, CONCEPT, CLASS, PROPERTY, RELATIONSHIP " +
                    "(CLASS/PROPERTY/RELATIONSHIP narrow to concepts of the given role)")
            @RequestParam(required = false) SearchType type,
            @Parameter(description = "Zdroj dat: NKD, ISMD, ALL")
            @RequestParam(required = false) SearchSource source,
            @Parameter(description = "Maximální počet výsledků (1-100)")
            @RequestParam(defaultValue = "20") int limit,
            @Parameter(description = "Offset pro stránkování")
            @RequestParam(defaultValue = "0") int offset,
            @Parameter(description = "Preferovaný jazyk")
            @RequestParam(defaultValue = "cs") String lang,
            @Parameter(description = "Filtrování podle IRI slovníku")
            @RequestParam(required = false) List<String> ontologyIri,
            @Parameter(description = "Filtrování podle typů vztahů: SUBCLASS, SUPERCLASS, EXACT_MATCH, PROPERTY_OF, RELATIONSHIP_OF")
            @RequestParam(required = false) List<RelationType> relationTypes,
            @AuthenticationPrincipal SecurityUser securityUser)
    {

        validateSearchParams(q, limit, offset);

        log.info("Search request: q='{}', type={}, source={}, limit={}, offset={}, lang={}, user={}",
                q.trim(), type, source, limit, offset, lang,
                securityUser != null ? securityUser.getUserId() : "anonymous");

        SearchResponseDto response = searchService.search(
                q.trim(), type, source, limit, offset, lang,
                ontologyIri, relationTypes, securityUser);

        return ResponseEntity.ok(ApiResponseDto.success(response, "Search completed successfully"));
    }

    private void validateSearchParams(String q, int limit, int offset) {
        if (q == null || q.trim().length() < minQueryLength) {
            throw new IllegalArgumentException(
                    "Search query must be at least " + minQueryLength + " characters long");
        }
        if (limit < 1 || limit > maxLimit) {
            throw new IllegalArgumentException("Limit must be between 1 and " + maxLimit);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("Offset must be non-negative");
        }
    }
}
