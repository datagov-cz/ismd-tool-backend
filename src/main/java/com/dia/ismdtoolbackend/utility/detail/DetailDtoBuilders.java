package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.controller.dto.CodeListDto;
import com.dia.ismdtoolbackend.controller.dto.DataTypeDto;
import com.dia.ismdtoolbackend.controller.dto.NonLegalSourceDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto.EnrichmentStatus;
import com.dia.ismdtoolbackend.enums.PropertyDataType;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaCzechCitationFormatter;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaEliParser;
import com.dia.ismdtoolbackend.utility.eli.ParsedEli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.utility.detail.DetailValues.*;

/** Builds the nested DTOs of a concept detail from raw export-map values. */
final class DetailDtoBuilders {

    private DetailDtoBuilders() {
    }

    /**
     * Maps the nested code-list object produced by {@code ConceptJsonMapper} into its DTO.
     * Absent or malformed structures yield null so the field is simply omitted.
     */
    @SuppressWarnings("unchecked")
    static CodeListDto buildCodeList(Object rawCodeList) {
        if (!(rawCodeList instanceof Map)) {
            return null;
        }
        Map<String, Object> codeList = (Map<String, Object>) rawCodeList;
        Object iri = codeList.get(JSON_IRI);
        Object dataset = codeList.get(DATOVA_SADA_V_NKOD);
        if (iri == null && dataset == null) {
            return null;
        }
        return CodeListDto.builder()
                .iri((String) iri)
                .typ((String) codeList.get("typ"))
                .datovaSadaVNkod((String) dataset)
                .build();
    }

    static List<NonLegalSourceDto> buildNonLegalSources(List<Map<String, Object>> rawSources) {
        if (rawSources == null || rawSources.isEmpty()) {
            return List.of();
        }
        List<NonLegalSourceDto> out = new ArrayList<>(rawSources.size());
        for (Map<String, Object> src : rawSources) {
            if (src == null) {
                continue;
            }
            out.add(NonLegalSourceDto.builder()
                    .iri(asString(src.get("iri")))
                    .typ(asString(src.get("typ")))
                    .nazev(asMultilingualMap(src.get(NAZEV)))
                    .popis(asMultilingualMap(src.get(POPIS)))
                    .url(asString(src.get("url")))
                    .build());
        }
        return out;
    }

    /**
     * Resolve any raw range value (bare code, {@code xsd:}/{@code rdfs:} prefix,
     * full IRI, Czech label) to a codelist DTO. Falls back to {@code Literal}
     * for null/empty/unrecognised input — mirrors the write-path default in
     * {@code ConceptCreator.addRangeProperty}.
     */
    static DataTypeDto buildDataTypeDto(Object rawRange) {
        String raw = rawRange instanceof String s ? s : null;
        return PropertyDataType.fromValue(raw)
                .orElse(PropertyDataType.LITERAL)
                .toDto();
    }

    /**
     * Build parse-only resolved-source DTOs for the concept-detail response.
     * Returns an empty list (never null) when the input is null/empty, so the
     * field always serializes as {@code []}. Never calls SPARQL — fragment URLs
     * are flagged {@code PENDING} for the FE to enrich via {@code /api/eli/resolve}.
     */
    static List<ResolvedLegalSourceDto> buildResolvedSources(List<String> urls) {
        if (urls == null || urls.isEmpty()) return List.of();
        List<ResolvedLegalSourceDto> out = new ArrayList<>(urls.size());
        for (String url : urls) {
            out.add(parseUrlToPendingDto(url));
        }
        return out;
    }

    private static ResolvedLegalSourceDto parseUrlToPendingDto(String url) {
        ParsedEli parsed = EsbirkaEliParser.parse(url);
        if (!parsed.isValid()) {
            return ResolvedLegalSourceDto.builder()
                    .originalUrl(url)
                    .enrichmentStatus(EnrichmentStatus.INVALID_IRI)
                    .build();
        }
        EnrichmentStatus status = parsed.isFragment()
                ? EnrichmentStatus.PENDING
                : EnrichmentStatus.SKIPPED_NON_FRAGMENT;
        return ResolvedLegalSourceDto.builder()
                .originalUrl(url)
                .domain(parsed.domain())
                .eliPath(parsed.eliPath())
                .level(parsed.level())
                .lawIri(parsed.lawIri())
                .versionIri(parsed.versionIri())
                .fragmentIri(parsed.fragmentIri())
                .lawNumber(parsed.lawNumber())
                .lawYear(parsed.lawYear())
                .sbirkaCode(parsed.sbirkaCode())
                .versionDate(parsed.versionDate())
                .fragmentSegments(parsed.fragmentSegments())
                .displayLabel(EsbirkaCzechCitationFormatter.buildDisplayLabel(parsed, null))
                .enrichmentStatus(status)
                .build();
    }
}
