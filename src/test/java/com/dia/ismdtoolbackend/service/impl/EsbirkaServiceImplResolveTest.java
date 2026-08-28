package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.EsbirkaSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto.EnrichmentStatus;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.models.eli.FragmentResolutionModel;
import com.dia.ismdtoolbackend.utility.eli.ParsedEli;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EsbirkaServiceImplResolveTest {

    private static final String LAW_URL =
            "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2000/361";
    private static final String VERSION_URL = LAW_URL + "/2024-04-01";
    private static final String FRAGMENT_URL = VERSION_URL + "/dokument/norma/cast_1/hlava_1/par_2/pism_d";
    private static final String LEGACY_FRAGMENT_URL =
            "https://opendata.eselpoint.cz/esel-esb/eli/cz/sb/2000/361/2024-04-01/dokument/norma/par_2/pism_d";

    private EsbirkaSparqlClient client;
    private EsbirkaFragmentResolutionCache cache;
    private EsbirkaServiceImpl service;

    @BeforeEach
    void setUp() {
        client = mock(EsbirkaSparqlClient.class);
        cache = mock(EsbirkaFragmentResolutionCache.class);
        // self is only used by the one-arg getLawContent overload, which this suite
        // never exercises; null keeps the constructor honest without a stub.
        service = new EsbirkaServiceImpl(client, cache, null);
    }

    @Test
    void resolveLegalSource_lawUrl_returnsSkippedNonFragment() {
        ResolvedLegalSourceDto dto = service.resolveLegalSource(LAW_URL);

        assertEquals(EnrichmentStatus.SKIPPED_NON_FRAGMENT, dto.getEnrichmentStatus());
        assertEquals(ParsedEli.Level.LAW, dto.getLevel());
        assertEquals("361", dto.getLawNumber());
        assertEquals(2000, dto.getLawYear());
        assertTrue(dto.getDisplayLabel().startsWith("Zákon č. 361/2000 Sb."));
        verify(cache, never()).fetch(any(), any(), any());
    }

    @Test
    void resolveLegalSource_versionUrl_returnsSkippedNonFragment() {
        ResolvedLegalSourceDto dto = service.resolveLegalSource(VERSION_URL);

        assertEquals(EnrichmentStatus.SKIPPED_NON_FRAGMENT, dto.getEnrichmentStatus());
        assertEquals(ParsedEli.Level.VERSION, dto.getLevel());
        assertEquals(LocalDate.of(2024, 4, 1), dto.getVersionDate());
        assertTrue(dto.getDisplayLabel().contains("(znění od 1. 4. 2024)"));
        verify(cache, never()).fetch(any(), any(), any());
    }

    @Test
    void resolveLegalSource_fragmentUrl_happyPath_returnsOk() {
        when(cache.fetch(any(), any(), any()))
                .thenReturn(Optional.of(new FragmentResolutionModel(
                        "§ 2 písm. d)", LocalDate.of(2024, 12, 31), true, null)));

        ResolvedLegalSourceDto dto = service.resolveLegalSource(FRAGMENT_URL);

        assertEquals(EnrichmentStatus.OK, dto.getEnrichmentStatus());
        assertEquals(ParsedEli.Level.FRAGMENT, dto.getLevel());
        assertEquals("§ 2 písm. d)", dto.getFragmentCitation());
        assertNull(dto.getFragmentBodyHtml(),
                "structural fragments without obsah must surface as null bodyHtml (not blank, not error)");
        assertNull(dto.getFragmentBody(),
                "null bodyHtml must yield null fragmentBody (mirrors the nullable contract)");
        assertEquals(LocalDate.of(2024, 12, 31), dto.getVersionValidUntil());
        assertTrue(dto.getIsLatestVersion());
        assertTrue(dto.getDisplayLabel().contains("§ 2 písm. d)"),
                "displayLabel should use SPARQL citation when available: " + dto.getDisplayLabel());
    }

    @Test
    void resolveLegalSource_fragmentUrl_withBodyHtml_surfacesObsah() {
        String body = "<var>1.</var> Předání bude uskutečněno do čtyřiceti pěti (45) dnů.";
        when(cache.fetch(any(), any(), any()))
                .thenReturn(Optional.of(new FragmentResolutionModel(
                        "Příloha č. 1 Čl. 13 bod 1", null, true, body)));

        ResolvedLegalSourceDto dto = service.resolveLegalSource(FRAGMENT_URL);

        assertEquals(EnrichmentStatus.OK, dto.getEnrichmentStatus());
        assertEquals(body, dto.getFragmentBodyHtml(),
                "fragmentBodyHtml must be passed verbatim from SPARQL obsah (preserves <var>/<a> tags for FE rendering)");
        assertEquals("1. Předání bude uskutečněno do čtyřiceti pěti (45) dnů.", dto.getFragmentBody(),
                "fragmentBody is the same obsah with markup stripped to raw text");
    }

    @Test
    void resolveLegalSource_fragmentUrl_emptyResult_returnsNotFound() {
        when(cache.fetch(any(), any(), any())).thenReturn(Optional.empty());

        ResolvedLegalSourceDto dto = service.resolveLegalSource(FRAGMENT_URL);

        assertEquals(EnrichmentStatus.NOT_FOUND, dto.getEnrichmentStatus());
        assertNull(dto.getFragmentCitation());
        assertNull(dto.getVersionValidUntil());
        assertNotNull(dto.getDisplayLabel(), "displayLabel must fall back to parse-only");
        assertTrue(dto.getDisplayLabel().contains("§ 2"), "fallback citation should include § 2");
    }

    @Test
    void resolveLegalSource_fragmentUrl_endpointDown_returnsUnavailable() {
        when(cache.fetch(any(), any(), any()))
                .thenThrow(new SparqlEndpointUnavailableException("e-Sbírka", "down"));

        ResolvedLegalSourceDto dto = service.resolveLegalSource(FRAGMENT_URL);

        assertEquals(EnrichmentStatus.UNAVAILABLE, dto.getEnrichmentStatus());
        assertNull(dto.getFragmentCitation());
        assertNotNull(dto.getDisplayLabel());
    }

    @Test
    void resolveLegalSource_invalidUrl_null_returnsInvalidIri() {
        ResolvedLegalSourceDto dto = service.resolveLegalSource(null);
        assertEquals(EnrichmentStatus.INVALID_IRI, dto.getEnrichmentStatus());
        assertNull(dto.getLevel());
        assertNull(dto.getDisplayLabel());
        verify(cache, never()).fetch(any(), any(), any());
    }

    @Test
    void resolveLegalSource_invalidUrl_garbage_returnsInvalidIri() {
        ResolvedLegalSourceDto dto = service.resolveLegalSource("not-a-url");
        assertEquals(EnrichmentStatus.INVALID_IRI, dto.getEnrichmentStatus());
    }

    @Test
    void resolveLegalSource_invalidUrl_foreignHost_returnsInvalidIri() {
        ResolvedLegalSourceDto dto = service.resolveLegalSource("https://example.com/eli/cz/sb/2000/361");
        assertEquals(EnrichmentStatus.INVALID_IRI, dto.getEnrichmentStatus());
    }

    @Test
    void resolveLegalSource_legacyHost_originalUrlPreservedCanonicalIrisInDto() {
        when(cache.fetch(any(), any(), any()))
                .thenReturn(Optional.of(new FragmentResolutionModel("§ 2 písm. d)", null, true, null)));

        ResolvedLegalSourceDto dto = service.resolveLegalSource(LEGACY_FRAGMENT_URL);

        assertEquals(LEGACY_FRAGMENT_URL, dto.getOriginalUrl(),
                "originalUrl preserved verbatim");
        assertTrue(dto.getLawIri().startsWith("https://opendata.eselpoint.gov.cz/esel-esb/"),
                "lawIri must use canonical host");
        assertTrue(dto.getFragmentIri().startsWith("https://opendata.eselpoint.gov.cz/esel-esb/"),
                "fragmentIri must use canonical host");
    }

}
