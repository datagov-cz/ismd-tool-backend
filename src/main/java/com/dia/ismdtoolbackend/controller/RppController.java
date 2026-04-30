package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.RppConfig;
import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.controller.dto.RppSearchResultDto;
import com.dia.ismdtoolbackend.service.RppService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.dia.constants.FormatConstants.Converter.LOG_REQUEST_ID;

@RestController
@RequestMapping("/api/rpp")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class RppController {

    private final RppService rppService;
    private final RppConfig rppConfig;

    @Operation(
            summary = "Vyhledávání agend v RPP",
            description = "Vrací seznam agend z Registru práv a povinností filtrovaný " +
                    "dle dotazu (kód nebo název, bez diakritiky)."
    )
    @GetMapping("/agenda/search")
    public ResponseEntity<ApiResponseDto<List<RppSearchResultDto>>> searchAgendas(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        try {
            int resolved = resolveLimit(limit);
            log.info("RPP agenda search, q: {}, limit: {}", q, resolved);
            List<RppSearchResultDto> results = rppService.searchAgendas(q, resolved);
            return ResponseEntity.ok(ApiResponseDto.success(results,
                    "Vyhledávání agend úspěšně provedeno."));
        } finally {
            MDC.remove(LOG_REQUEST_ID);
        }
    }

    @Operation(
            summary = "Vyhledávání informačních systémů (ISVS) v RPP",
            description = "Vrací seznam ISVS z Registru práv a povinností. " +
                    "Pokud je uveden preferredAgendaCode, ISVS obsluhující tuto agendu jsou řazeny první."
    )
    @GetMapping("/ais/search")
    public ResponseEntity<ApiResponseDto<List<RppSearchResultDto>>> searchIsvs(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String preferredAgendaCode) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        try {
            int resolved = resolveLimit(limit);
            log.info("RPP ISVS search, q: {}, limit: {}, preferredAgendaCode: {}",
                    q, resolved, preferredAgendaCode);
            List<RppSearchResultDto> results = rppService.searchIsvs(q, resolved, preferredAgendaCode);
            return ResponseEntity.ok(ApiResponseDto.success(results,
                    "Vyhledávání informačních systémů úspěšně provedeno."));
        } finally {
            MDC.remove(LOG_REQUEST_ID);
        }
    }

    private int resolveLimit(Integer limit) {
        int max = rppConfig.getSearch().getMaxLimit();
        if (limit == null) return rppConfig.getSearch().getDefaultLimit();
        if (limit < 1 || limit > max) {
            throw new IllegalArgumentException(
                    "Parametr limit musí být v rozsahu 1 až " + max + ".");
        }
        return limit;
    }
}
