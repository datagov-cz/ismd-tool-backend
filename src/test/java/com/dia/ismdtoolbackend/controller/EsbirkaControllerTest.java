package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.EsbirkaConfig;
import com.dia.ismdtoolbackend.config.GlobalExceptionHandler;
import com.dia.ismdtoolbackend.config.security.TestOntologySecurityService;
import com.dia.ismdtoolbackend.config.security.TestSecurityConfig;
import com.dia.ismdtoolbackend.config.security.WithMockSecurityUser;
import com.dia.ismdtoolbackend.controller.dto.FragmentDto;
import com.dia.ismdtoolbackend.controller.dto.LawDto;
import com.dia.ismdtoolbackend.controller.dto.LawVersionDto;
import com.dia.ismdtoolbackend.exception.EsbirkaUnavailableException;
import com.dia.ismdtoolbackend.service.EsbirkaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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
                org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
                org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration.class
        })
@Import({TestSecurityConfig.class, TestOntologySecurityService.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
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
    @WithMockSecurityUser(userId = "user123")
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
    @WithMockSecurityUser(userId = "user123")
    void lawSearchDefaultLimitWhenMissing() throws Exception {
        when(esbirkaService.searchLaws(any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/eli/law/search"))
                .andExpect(status().isOk());

        ArgumentCaptor<Integer> limitCap = ArgumentCaptor.forClass(Integer.class);
        verify(esbirkaService).searchLaws(any(), limitCap.capture());
        assertEquals(20, limitCap.getValue());
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void lawSearchLimitZeroReturns400() throws Exception {
        mockMvc.perform(get("/api/eli/law/search").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Parametr limit musí být v rozsahu 1 až 50."));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void lawSearchLimitAboveMaxReturns400() throws Exception {
        mockMvc.perform(get("/api/eli/law/search").param("limit", "51"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void lawSearchMissingQueryForwardedAsNull() throws Exception {
        when(esbirkaService.searchLaws(any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/eli/law/search").param("limit", "10"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> qCap = ArgumentCaptor.forClass(String.class);
        verify(esbirkaService).searchLaws(qCap.capture(), anyInt());
        assertNull(qCap.getValue());
    }

    @Test
    void lawSearchUnauthenticatedReturns403() throws Exception {
        mockMvc.perform(get("/api/eli/law/search"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void lawSearchService503BubblesUp() throws Exception {
        when(esbirkaService.searchLaws(any(), anyInt()))
                .thenThrow(new EsbirkaUnavailableException("e-Sbírka law search fetch failed"));

        mockMvc.perform(get("/api/eli/law/search"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("e-Sbírka data nejsou momentálně dostupná."));
    }

    // -------- /law/versions --------

    @Test
    @WithMockSecurityUser(userId = "user123")
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
    @WithMockSecurityUser(userId = "user123")
    void versionsInvalidIriReturns400() throws Exception {
        mockMvc.perform(get("/api/eli/law/versions").param("lawIri", "https://example.org/foo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Neplatný identifikátor právního aktu."));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void versionsMissingParamReturns400() throws Exception {
        mockMvc.perform(get("/api/eli/law/versions"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void versionsService503BubblesUp() throws Exception {
        when(esbirkaService.getVersions(LAW_IRI))
                .thenThrow(new EsbirkaUnavailableException("e-Sbírka version list fetch failed"));

        mockMvc.perform(get("/api/eli/law/versions").param("lawIri", LAW_IRI))
                .andExpect(status().isServiceUnavailable());
    }

    // -------- /law/fragments --------

    @Test
    @WithMockSecurityUser(userId = "user123")
    void fragmentsHappyPathReturnsTree() throws Exception {
        FragmentDto root = new FragmentDto(VERSION_IRI + "/par_1",
                "/eli/cz/sb/2006/187/2026-04-01/par_1",
                "par", "§ 1", "0001", new ArrayList<>());
        when(esbirkaService.getFragments(VERSION_IRI)).thenReturn(List.of(root));

        mockMvc.perform(get("/api/eli/law/fragments").param("versionIri", VERSION_IRI))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].kind").value("par"))
                .andExpect(jsonPath("$.data[0].citation").value("§ 1"));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void fragmentsInvalidIriReturns400() throws Exception {
        mockMvc.perform(get("/api/eli/law/fragments").param("versionIri", "javascript:alert(1)"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Neplatný identifikátor znění právního aktu."));
    }

    @Test
    void fragmentsUnauthenticatedReturns403() throws Exception {
        mockMvc.perform(get("/api/eli/law/fragments").param("versionIri", VERSION_IRI))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void fragmentsService503BubblesUp() throws Exception {
        when(esbirkaService.getFragments(VERSION_IRI))
                .thenThrow(new EsbirkaUnavailableException("e-Sbírka fragment tree fetch failed"));

        mockMvc.perform(get("/api/eli/law/fragments").param("versionIri", VERSION_IRI))
                .andExpect(status().isServiceUnavailable());
    }
}
