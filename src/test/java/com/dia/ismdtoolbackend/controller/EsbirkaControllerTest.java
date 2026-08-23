package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.EsbirkaConfig;
import com.dia.ismdtoolbackend.config.GlobalExceptionHandler;
import com.dia.ismdtoolbackend.config.security.TestOntologySecurityService;
import com.dia.ismdtoolbackend.config.security.TestSecurityConfig;
import com.dia.ismdtoolbackend.config.security.WithMockSecurityUser;
import com.dia.ismdtoolbackend.controller.dto.FragmentDto;
import com.dia.ismdtoolbackend.controller.dto.LawContentDto;
import com.dia.ismdtoolbackend.controller.dto.LawDto;
import com.dia.ismdtoolbackend.controller.dto.LawSearchGroupDto;
import com.dia.ismdtoolbackend.controller.dto.LawSearchResultDto;
import com.dia.ismdtoolbackend.controller.dto.LawVersionDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto.EnrichmentStatus;
import com.dia.ismdtoolbackend.utility.eli.ParsedEli;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.service.EsbirkaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EsbirkaController.class,
        excludeAutoConfiguration = {
                org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class,
                org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
                org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration.class,
                org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration.class,
                org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration.class
        })
@Import({TestSecurityConfig.class, TestOntologySecurityService.class, GlobalExceptionHandler.class})
@ActiveProfiles("junit")
class EsbirkaControllerTest {

    private static final String LAW_IRI = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187";
    private static final String VERSION_IRI = LAW_IRI + "/2026-04-01";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EsbirkaService esbirkaService;

    @MockitoBean
    private EsbirkaConfig esbirkaConfig;

    @BeforeEach
    void setUp() {
        EsbirkaConfig.Search search = new EsbirkaConfig.Search();
        search.setDefaultLimit(20);
        search.setMaxLimit(50);
        when(esbirkaConfig.getSearch()).thenReturn(search);
    }

    // -------- /law/search --------

    @Test
    void lawSearchHappyPathReturnsEnvelope() throws Exception {
        LawDto dto = new LawDto(LAW_IRI, "/eli/cz/sb/2006/187",
                "https://opendata.eselpoint.gov.cz", "187/2006 Sb.",
                "Zákon č. 187/2006 Sb.", "187", 2006, "sb");
        when(esbirkaService.searchLaws(eq("187"), eq(5))).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/eli/law/search").param("q", "187").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].iri").value(LAW_IRI))
                .andExpect(jsonPath("$.data[0].eliPath").value("/eli/cz/sb/2006/187"))
                .andExpect(jsonPath("$.data[0].displayName").value("Zákon č. 187/2006 Sb."))
                .andExpect(jsonPath("$.message").value("Vyhledávání právních aktů úspěšně provedeno."));
    }

    @Test
    void lawSearchDefaultLimitWhenMissing() throws Exception {
        when(esbirkaService.searchLaws(any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/eli/law/search"))
                .andExpect(status().isOk());

        ArgumentCaptor<Integer> limitCap = ArgumentCaptor.forClass(Integer.class);
        verify(esbirkaService).searchLaws(any(), limitCap.capture());
        assertEquals(20, limitCap.getValue());
    }

    @Test
    void lawSearchLimitZeroReturns400() throws Exception {
        mockMvc.perform(get("/api/eli/law/search").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Parametr limit musí být v rozsahu 1 až 50."));
    }

    @Test
    void lawSearchLimitAboveMaxReturns400() throws Exception {
        mockMvc.perform(get("/api/eli/law/search").param("limit", "51"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lawSearchMissingQueryForwardedAsNull() throws Exception {
        when(esbirkaService.searchLaws(any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/eli/law/search").param("limit", "10"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> qCap = ArgumentCaptor.forClass(String.class);
        verify(esbirkaService).searchLaws(qCap.capture(), anyInt());
        assertNull(qCap.getValue());
    }

    @Test
    void lawSearchService503BubblesUp() throws Exception {
        when(esbirkaService.searchLaws(any(), anyInt()))
                .thenThrow(new SparqlEndpointUnavailableException("e-Sbírka", "e-Sbírka law search fetch failed"));

        mockMvc.perform(get("/api/eli/law/search"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("e-Sbírka data nejsou momentálně dostupná."));
    }

    // -------- /law/versions --------

    @Test
    void versionsHappyPathReturnsList() throws Exception {
        LawVersionDto v = new LawVersionDto(VERSION_IRI, "/eli/cz/sb/2006/187/2026-04-01",
                LocalDate.of(2026, 4, 1), null, "https://t/jednorazové", true);
        when(esbirkaService.getVersions(LAW_IRI)).thenReturn(List.of(v));

        mockMvc.perform(get("/api/eli/law/versions").param("lawIri", LAW_IRI))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].iri").value(VERSION_IRI))
                .andExpect(jsonPath("$.data[0].latest").value(true));
    }

    @Test
    void versionsInvalidIriReturns400() throws Exception {
        mockMvc.perform(get("/api/eli/law/versions").param("lawIri", "https://example.org/foo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Neplatný identifikátor právního aktu."));
    }

    @Test
    void versionsMissingParamReturns400() throws Exception {
        mockMvc.perform(get("/api/eli/law/versions"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void versionsService503BubblesUp() throws Exception {
        when(esbirkaService.getVersions(LAW_IRI))
                .thenThrow(new SparqlEndpointUnavailableException("e-Sbírka", "e-Sbírka version list fetch failed"));

        mockMvc.perform(get("/api/eli/law/versions").param("lawIri", LAW_IRI))
                .andExpect(status().isServiceUnavailable());
    }

    // -------- /law/fragments --------

    @Test
    void fragmentsHappyPathReturnsTree() throws Exception {
        FragmentDto root = new FragmentDto(VERSION_IRI + "/par_1",
                "/eli/cz/sb/2006/187/2026-04-01/par_1",
                "par", "§ 1", "0001", null, new ArrayList<>());
        when(esbirkaService.getFragments(VERSION_IRI)).thenReturn(List.of(root));

        mockMvc.perform(get("/api/eli/law/fragments").param("versionIri", VERSION_IRI))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].kind").value("par"))
                .andExpect(jsonPath("$.data[0].citation").value("§ 1"));
    }

    @Test
    void fragmentsInvalidIriReturns400() throws Exception {
        mockMvc.perform(get("/api/eli/law/fragments").param("versionIri", "javascript:alert(1)"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Neplatný identifikátor znění právního aktu."));
    }

    @Test
    void fragmentsService503BubblesUp() throws Exception {
        when(esbirkaService.getFragments(VERSION_IRI))
                .thenThrow(new SparqlEndpointUnavailableException("e-Sbírka", "e-Sbírka fragment tree fetch failed"));

        mockMvc.perform(get("/api/eli/law/fragments").param("versionIri", VERSION_IRI))
                .andExpect(status().isServiceUnavailable());
    }

    // -------- /law/search/grouped --------

    @Test
    void groupedSearchReturnsGroupsAndAmbiguityFlag() throws Exception {
        LawDto a = new LawDto(LAW_IRI, "/eli/cz/sb/2006/187", "https://opendata.eselpoint.gov.cz",
                "49/2026 Sb.", "Zákon č. 49/2026 Sb.", "49", 2026, "sb");
        LawDto b = new LawDto(LAW_IRI, "/eli/cz/sb/2006/187", "https://opendata.eselpoint.gov.cz",
                "49/1997 Sb.", "Zákon č. 49/1997 Sb.", "49", 1997, "sb");
        LawSearchResultDto dto = LawSearchResultDto.builder()
                .query("49")
                .ambiguous(true)
                .totalMatches(2)
                .truncated(false)
                .groups(List.of(LawSearchGroupDto.builder()
                        .cislo("49")
                        .count(2)
                        .exactNumberMatch(true)
                        .laws(List.of(a, b))
                        .build()))
                .build();
        when(esbirkaService.searchLawsGrouped("49", 20)).thenReturn(dto);

        mockMvc.perform(get("/api/eli/law/search/grouped").param("q", "49"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.query").value("49"))
                .andExpect(jsonPath("$.data.ambiguous").value(true))
                .andExpect(jsonPath("$.data.totalMatches").value(2))
                .andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.groups.length()").value(1))
                .andExpect(jsonPath("$.data.groups[0].cislo").value("49"))
                .andExpect(jsonPath("$.data.groups[0].count").value(2))
                .andExpect(jsonPath("$.data.groups[0].exactNumberMatch").value(true))
                .andExpect(jsonPath("$.data.groups[0].laws.length()").value(2))
                .andExpect(jsonPath("$.data.groups[0].laws[0].citace").value("49/2026 Sb."));
    }

    @Test
    void groupedSearchRejectsOutOfRangeLimit() throws Exception {
        mockMvc.perform(get("/api/eli/law/search/grouped").param("q", "49").param("limit", "999"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void groupedSearchService503BubblesUp() throws Exception {
        when(esbirkaService.searchLawsGrouped("49", 20))
                .thenThrow(new SparqlEndpointUnavailableException("e-Sbírka", "e-Sbírka law search failed"));

        mockMvc.perform(get("/api/eli/law/search/grouped").param("q", "49"))
                .andExpect(status().isServiceUnavailable());
    }

    // -------- /law/content --------

    @Test
    void contentHappyPathReturnsHeaderVersionsAndTree() throws Exception {
        FragmentDto child = new FragmentDto(VERSION_IRI + "/par_1/odst_1",
                "/eli/cz/sb/2006/187/2026-04-01/par_1/odst_1",
                "odst", "§ 1 odst. 1", "0002", "<var>1.</var> Tělo.", new ArrayList<>());
        List<FragmentDto> children = new ArrayList<>();
        children.add(child);
        FragmentDto root = new FragmentDto(VERSION_IRI + "/par_1",
                "/eli/cz/sb/2006/187/2026-04-01/par_1",
                "par", "§ 1", "0001", null, children);
        LawVersionDto v = new LawVersionDto(VERSION_IRI, "/eli/cz/sb/2006/187/2026-04-01",
                LocalDate.of(2026, 4, 1), null, "t", true);
        LawContentDto dto = LawContentDto.builder()
                .lawIri(LAW_IRI)
                .citace("187/2006 Sb.")
                .versionIri(VERSION_IRI)
                .versionEliPath("/eli/cz/sb/2006/187/2026-04-01")
                .versionDate(LocalDate.of(2026, 4, 1))
                .versions(List.of(v))
                .fragments(List.of(root))
                .build();
        when(esbirkaService.getLawContent("187/2006", null)).thenReturn(dto);

        mockMvc.perform(get("/api/eli/law/content").param("law", "187/2006"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.lawIri").value(LAW_IRI))
                .andExpect(jsonPath("$.data.citace").value("187/2006 Sb."))
                .andExpect(jsonPath("$.data.versionIri").value(VERSION_IRI))
                .andExpect(jsonPath("$.data.versions.length()").value(1))
                .andExpect(jsonPath("$.data.fragments.length()").value(1))
                .andExpect(jsonPath("$.data.fragments[0].bodyHtml").value(nullValue()))
                .andExpect(jsonPath("$.data.fragments[0].children[0].bodyHtml").value("<var>1.</var> Tělo."))
                .andExpect(jsonPath("$.message").value("Celé znění právního aktu úspěšně načteno."));
    }

    @Test
    void contentPassesSelectedVersionIriToService() throws Exception {
        String olderIri = LAW_IRI + "/2020-01-01";
        LawContentDto dto = LawContentDto.builder()
                .lawIri(LAW_IRI)
                .citace("187/2006 Sb.")
                .versionIri(olderIri)
                .versionEliPath("/eli/cz/sb/2006/187/2020-01-01")
                .versionDate(LocalDate.of(2020, 1, 1))
                .versionLatest(false)
                .versions(new ArrayList<>())
                .fragments(new ArrayList<>())
                .build();
        when(esbirkaService.getLawContent("187/2006", olderIri)).thenReturn(dto);

        mockMvc.perform(get("/api/eli/law/content")
                        .param("law", "187/2006")
                        .param("versionIri", olderIri))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionIri").value(olderIri))
                .andExpect(jsonPath("$.data.versionLatest").value(false));
    }

    @Test
    void contentVersionIriOfAnotherLawReturns400() throws Exception {
        String foreign = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/262/2026-04-01";
        when(esbirkaService.getLawContent("187/2006", foreign))
                .thenThrow(new IllegalArgumentException(
                        "Znění " + foreign + " nepatří k právnímu aktu č. 187/2006."));

        mockMvc.perform(get("/api/eli/law/content")
                        .param("law", "187/2006")
                        .param("versionIri", foreign))
                .andExpect(status().isBadRequest());
    }

    @Test
    void contentPartialInputReturns400() throws Exception {
        when(esbirkaService.getLawContent("49", null))
                .thenThrow(new IllegalArgumentException("Referenci zadejte ve tvaru číslo/rok (např. 49/1997)."));
        mockMvc.perform(get("/api/eli/law/content").param("law", "49"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Referenci zadejte ve tvaru číslo/rok (např. 49/1997)."));
    }

    @Test
    void contentUnknownLawReturns400() throws Exception {
        when(esbirkaService.getLawContent("999/1997", null))
                .thenThrow(new IllegalArgumentException("Právní akt č. 999/1997 nebyl nalezen."));
        mockMvc.perform(get("/api/eli/law/content").param("law", "999/1997"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Právní akt č. 999/1997 nebyl nalezen."));
    }

    @Test
    void contentMissingParamReturns400() throws Exception {
        mockMvc.perform(get("/api/eli/law/content"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void contentService503BubblesUp() throws Exception {
        when(esbirkaService.getLawContent("187/2006", null))
                .thenThrow(new SparqlEndpointUnavailableException("e-Sbírka", "e-Sbírka version content fetch failed"));

        mockMvc.perform(get("/api/eli/law/content").param("law", "187/2006"))
                .andExpect(status().isServiceUnavailable());
    }

    // -------- /resolve --------

    @Test
    void resolveValidFragmentUrlReturns200WithDto() throws Exception {
        String fragmentUrl = VERSION_IRI + "/dokument/norma/par_2/pism_d";
        ResolvedLegalSourceDto dto = ResolvedLegalSourceDto.builder()
                .originalUrl(fragmentUrl)
                .level(ParsedEli.Level.FRAGMENT)
                .fragmentCitation("§ 2 písm. d)")
                .displayLabel("Zákon č. 187/2006 Sb., § 2 písm. d)")
                .enrichmentStatus(EnrichmentStatus.OK)
                .build();
        when(esbirkaService.resolveLegalSource(fragmentUrl)).thenReturn(dto);

        mockMvc.perform(get("/api/eli/resolve").param("iri", fragmentUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.enrichmentStatus").value("OK"))
                .andExpect(jsonPath("$.data.fragmentCitation").value("§ 2 písm. d)"));
    }

    @Test
    void resolveInvalidUrlReturns200WithInvalidIriStatus() throws Exception {
        ResolvedLegalSourceDto dto = ResolvedLegalSourceDto.builder()
                .originalUrl("garbage")
                .enrichmentStatus(EnrichmentStatus.INVALID_IRI)
                .build();
        when(esbirkaService.resolveLegalSource("garbage")).thenReturn(dto);

        mockMvc.perform(get("/api/eli/resolve").param("iri", "garbage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enrichmentStatus").value("INVALID_IRI"));
    }

    @Test
    void resolveESbirkaDownReturns200WithUnavailableStatus() throws Exception {
        String fragmentUrl = VERSION_IRI + "/dokument/norma/par_2/pism_d";
        ResolvedLegalSourceDto dto = ResolvedLegalSourceDto.builder()
                .originalUrl(fragmentUrl)
                .level(ParsedEli.Level.FRAGMENT)
                .enrichmentStatus(EnrichmentStatus.UNAVAILABLE)
                .build();
        when(esbirkaService.resolveLegalSource(fragmentUrl)).thenReturn(dto);

        mockMvc.perform(get("/api/eli/resolve").param("iri", fragmentUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enrichmentStatus").value("UNAVAILABLE"));
    }

    @Test
    void resolveUnauthenticatedReturns200() throws Exception {
        // /resolve is a public endpoint (mirrors /concept/{slug}/detail being public).
        ResolvedLegalSourceDto dto = ResolvedLegalSourceDto.builder()
                .originalUrl("anything")
                .enrichmentStatus(EnrichmentStatus.INVALID_IRI)
                .build();
        when(esbirkaService.resolveLegalSource("anything")).thenReturn(dto);

        mockMvc.perform(get("/api/eli/resolve").param("iri", "anything"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enrichmentStatus").value("INVALID_IRI"));
    }
}
