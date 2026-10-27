package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.EsbirkaClient;
import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto.EnrichmentStatus;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.models.eli.FragmentResolutionModel;
import com.dia.ismdtoolbackend.utility.eli.ParsedEli;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static com.dia.ismdtoolbackend.testsupport.fixtures.EsbirkaFixtures.LAW_361_2000;
import static com.dia.ismdtoolbackend.testsupport.fixtures.EsbirkaFixtures.legacyLawIri;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EsbirkaServiceImplResolveTest {

    private static final String LAW_URL = LAW_361_2000;
    private static final String VERSION_URL = LAW_URL + "/2024-04-01";
    private static final String FRAGMENT_URL = VERSION_URL + "/dokument/norma/cast_1/hlava_1/par_2/pism_d";
    private static final String LEGACY_FRAGMENT_URL =
            legacyLawIri("361", 2000) + "/2024-04-01/dokument/norma/par_2/pism_d";

    private EsbirkaClient client;
    private EsbirkaFragmentResolutionCache cache;
    private EsbirkaSubtreeBodyCache subtreeBodyCache;
    private EsbirkaServiceImpl service;

    @BeforeEach
    void setUp() {
        client = mock(EsbirkaClient.class);
        cache = mock(EsbirkaFragmentResolutionCache.class);
        subtreeBodyCache = mock(EsbirkaSubtreeBodyCache.class);
        // Working baseline: the version resolves, it simply holds no subtree for this fragment.
        // Without it the degraded (fallback-unavailable) path would be the fixture default and
        // every "no body" assertion would pass for the wrong reason.
        lenient().when(subtreeBodyCache.bodiesByFragmentIri(any())).thenReturn(Map.of());
        service = EsbirkaServiceWiring.build(client, cache, subtreeBodyCache);
    }

    @Test
    void resolveLegalSource_lawUrl_returnsSkippedNonFragment() {
        ResolvedLegalSourceDto dto = service.resolveLegalSource(LAW_URL);

        assertThat(dto.getEnrichmentStatus()).isEqualTo(EnrichmentStatus.SKIPPED_NON_FRAGMENT);
        assertThat(dto.getLevel()).isEqualTo(ParsedEli.Level.LAW);
        assertThat(dto.getLawNumber()).isEqualTo("361");
        assertThat(dto.getLawYear()).isEqualTo(2000);
        assertThat(dto.getDisplayLabel()).startsWith("Zákon č. 361/2000 Sb.");
        verify(cache, never()).fetch(any(), any(), any());
    }

    @Test
    void resolveLegalSource_versionUrl_returnsSkippedNonFragment() {
        ResolvedLegalSourceDto dto = service.resolveLegalSource(VERSION_URL);

        assertThat(dto.getEnrichmentStatus()).isEqualTo(EnrichmentStatus.SKIPPED_NON_FRAGMENT);
        assertThat(dto.getLevel()).isEqualTo(ParsedEli.Level.VERSION);
        assertThat(dto.getVersionDate()).isEqualTo(LocalDate.of(2024, 4, 1));
        assertThat(dto.getDisplayLabel()).contains("(znění od 1. 4. 2024)");
        verify(cache, never()).fetch(any(), any(), any());
    }

    @Test
    void resolveLegalSource_fragmentUrl_happyPath_returnsOk() {
        when(cache.fetch(any(), any(), any()))
                .thenReturn(Optional.of(new FragmentResolutionModel(
                        "§ 2 písm. d)", LocalDate.of(2024, 12, 31), true, null)));

        ResolvedLegalSourceDto dto = service.resolveLegalSource(FRAGMENT_URL);

        assertThat(dto.getEnrichmentStatus()).isEqualTo(EnrichmentStatus.OK);
        assertThat(dto.getLevel()).isEqualTo(ParsedEli.Level.FRAGMENT);
        assertThat(dto.getFragmentCitation()).isEqualTo("§ 2 písm. d)");
        assertThat(dto.getFragmentBodyHtml())
                .as("structural fragments without obsah must surface as null bodyHtml (not blank, not error)")
                .isNull();
        assertThat(dto.getFragmentBody())
                .as("null bodyHtml must yield null fragmentBody (mirrors the nullable contract)")
                .isNull();
        assertThat(dto.getVersionValidUntil()).isEqualTo(LocalDate.of(2024, 12, 31));
        assertThat(dto.getIsLatestVersion()).isTrue();
        assertThat(dto.getDisplayLabel())
                .as("displayLabel should use SPARQL citation when available: " + dto.getDisplayLabel())
                .contains("§ 2 písm. d)");
    }

    @Test
    void resolveLegalSource_fragmentUrl_withBodyHtml_surfacesObsah() {
        String body = "<var>1.</var> Předání bude uskutečněno do čtyřiceti pěti (45) dnů.";
        when(cache.fetch(any(), any(), any()))
                .thenReturn(Optional.of(new FragmentResolutionModel(
                        "Příloha č. 1 Čl. 13 bod 1", null, true, body)));

        ResolvedLegalSourceDto dto = service.resolveLegalSource(FRAGMENT_URL);

        assertThat(dto.getEnrichmentStatus()).isEqualTo(EnrichmentStatus.OK);
        assertThat(dto.getFragmentBodyHtml())
                .as("fragmentBodyHtml must be passed verbatim from SPARQL obsah (preserves <var>/<a> tags for FE rendering)")
                .isEqualTo(body);
        assertThat(dto.getFragmentBody())
                .as("fragmentBody is the same obsah with markup stripped to raw text")
                .isEqualTo("1. Předání bude uskutečněno do čtyřiceti pěti (45) dnů.");
    }

    @Test
    void resolveLegalSource_containerRoot_noObsah_assemblesSubtreeBody() {
        String containerUrl = VERSION_URL + "/dokument/poznamkypodcarou";
        String assembled = "<section data-eli=\"/x\" data-iri=\"\" data-kind=\"ppc\">Poznámka 1</section>";
        when(cache.fetch(any(), any(), any()))
                .thenReturn(Optional.of(new FragmentResolutionModel(null, null, true, null)));
        when(subtreeBodyCache.bodiesByFragmentIri(VERSION_URL))
                .thenReturn(Map.of(containerUrl, assembled));

        ResolvedLegalSourceDto dto = service.resolveLegalSource(containerUrl);

        assertThat(dto.getEnrichmentStatus()).isEqualTo(EnrichmentStatus.OK);
        assertThat(dto.getFragmentBodyHtml())
                .as("a container carrying no obsah must surface its descendants' assembled body")
                .isEqualTo(assembled);
        assertThat(dto.getFragmentBody())
                .as("fragmentBody is the assembled body with markup stripped")
                .isEqualTo("Poznámka 1");
        assertThat(dto.getDisplayLabel()).contains("Poznámky pod čarou");
    }

    @Test
    void resolveLegalSource_fragmentWithOwnObsah_doesNotAssembleSubtree() {
        when(cache.fetch(any(), any(), any()))
                .thenReturn(Optional.of(new FragmentResolutionModel("§ 2", null, true, "<p>vlastní text</p>")));

        ResolvedLegalSourceDto dto = service.resolveLegalSource(FRAGMENT_URL);

        assertThat(dto.getFragmentBodyHtml()).isEqualTo("<p>vlastní text</p>");
        verify(subtreeBodyCache, never()).bodiesByFragmentIri(any());
    }

    @Test
    void resolveLegalSource_subtreeAssemblyUnavailable_stillReturnsOk() {
        when(cache.fetch(any(), any(), any()))
                .thenReturn(Optional.of(new FragmentResolutionModel("Poznámky pod čarou", null, true, null)));
        when(subtreeBodyCache.bodiesByFragmentIri(any()))
                .thenThrow(new SparqlEndpointUnavailableException("e-Sbírka", "down"));

        ResolvedLegalSourceDto dto = service.resolveLegalSource(VERSION_URL + "/dokument/poznamkypodcarou");

        assertThat(dto.getEnrichmentStatus())
                .as("a failed body assembly must not downgrade an otherwise-resolved fragment")
                .isEqualTo(EnrichmentStatus.OK);
        assertThat(dto.getFragmentBodyHtml()).isNull();
        assertThat(dto.getFragmentCitation()).isEqualTo("Poznámky pod čarou");
    }

    @Test
    void resolveLegalSource_fragmentUrl_emptyResult_returnsNotFound() {
        when(cache.fetch(any(), any(), any())).thenReturn(Optional.empty());

        ResolvedLegalSourceDto dto = service.resolveLegalSource(FRAGMENT_URL);

        assertThat(dto.getEnrichmentStatus()).isEqualTo(EnrichmentStatus.NOT_FOUND);
        assertThat(dto.getFragmentCitation()).isNull();
        assertThat(dto.getVersionValidUntil()).isNull();
        assertThat(dto.getDisplayLabel()).as("displayLabel must fall back to parse-only").isNotNull();
        assertThat(dto.getDisplayLabel()).as("fallback citation should include § 2").contains("§ 2");
    }

    @Test
    void resolveLegalSource_fragmentUrl_endpointDown_returnsUnavailable() {
        when(cache.fetch(any(), any(), any()))
                .thenThrow(new SparqlEndpointUnavailableException("e-Sbírka", "down"));

        ResolvedLegalSourceDto dto = service.resolveLegalSource(FRAGMENT_URL);

        assertThat(dto.getEnrichmentStatus()).isEqualTo(EnrichmentStatus.UNAVAILABLE);
        assertThat(dto.getFragmentCitation()).isNull();
        assertThat(dto.getDisplayLabel()).isNotNull();
    }

    @Test
    void resolveLegalSource_invalidUrl_null_returnsInvalidIri() {
        ResolvedLegalSourceDto dto = service.resolveLegalSource(null);
        assertThat(dto.getEnrichmentStatus()).isEqualTo(EnrichmentStatus.INVALID_IRI);
        assertThat(dto.getLevel()).isNull();
        assertThat(dto.getDisplayLabel()).isNull();
        verify(cache, never()).fetch(any(), any(), any());
    }

    @Test
    void resolveLegalSource_invalidUrl_garbage_returnsInvalidIri() {
        ResolvedLegalSourceDto dto = service.resolveLegalSource("not-a-url");
        assertThat(dto.getEnrichmentStatus()).isEqualTo(EnrichmentStatus.INVALID_IRI);
    }

    @Test
    void resolveLegalSource_invalidUrl_foreignHost_returnsInvalidIri() {
        ResolvedLegalSourceDto dto = service.resolveLegalSource("https://example.com/eli/cz/sb/2000/361");
        assertThat(dto.getEnrichmentStatus()).isEqualTo(EnrichmentStatus.INVALID_IRI);
    }

    @Test
    void resolveLegalSource_legacyHost_originalUrlPreservedCanonicalIrisInDto() {
        when(cache.fetch(any(), any(), any()))
                .thenReturn(Optional.of(new FragmentResolutionModel("§ 2 písm. d)", null, true, null)));

        ResolvedLegalSourceDto dto = service.resolveLegalSource(LEGACY_FRAGMENT_URL);

        assertThat(dto.getOriginalUrl()).as("originalUrl preserved verbatim").isEqualTo(LEGACY_FRAGMENT_URL);
        assertThat(dto.getLawIri())
                .as("lawIri must use canonical host")
                .startsWith("https://opendata.eselpoint.gov.cz/esel-esb/");
        assertThat(dto.getFragmentIri())
                .as("fragmentIri must use canonical host")
                .startsWith("https://opendata.eselpoint.gov.cz/esel-esb/");
    }

}
