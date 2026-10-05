package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.GlobalExceptionHandler;
import com.dia.ismdtoolbackend.config.security.TestOntologySecurityService;
import com.dia.ismdtoolbackend.config.security.TestSecurityConfig;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.models.nkod.NkodCodelist;
import com.dia.ismdtoolbackend.service.nkod.NkodCodelistService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = NkodCodelistController.class,
        excludeAutoConfiguration = {
                org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class,
                org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
                org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration.class,
                org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration.class,
                org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration.class
        })
@Import({TestSecurityConfig.class, TestOntologySecurityService.class, GlobalExceptionHandler.class})
@ActiveProfiles("junit")
class NkodCodelistControllerTest {

    private static final String POHLAVI = "https://data.gov.cz/zdroj/datové-sady/17651921/5ccc4289";
    private static final String POHLAVI_2025 = "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/číselníky/151/2025-01-01";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NkodCodelistService nkodCodelistService;

    @Test
    void list_returnsTheEntriesInServiceOrder() throws Exception {
        when(nkodCodelistService.list(null)).thenReturn(List.of(
                NkodCodelist.builder()
                        .datasetIri("https://data.gov.cz/zdroj/datové-sady/00216208/isvav")
                        .codeListIri("https://data.mff.cuni.cz/zdroj/číselníky/isvav")
                        .title("Klasifikace oborů ISVaV").publisher("Univerzita Karlova").build(),
                NkodCodelist.builder()
                        .datasetIri(POHLAVI).codeListIri(POHLAVI_2025)
                        .title("Pohlaví").publisher("Digitální a informační agentura")
                        .description("Datová sada POHLAVI").codeListNumber("151").validFrom("2025-01-01").build()));

        mockMvc.perform(get("/api/codelist/nkod"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].title").value("Klasifikace oborů ISVaV"))
                .andExpect(jsonPath("$.data[1].datasetIri").value(POHLAVI))
                .andExpect(jsonPath("$.data[1].codeListIri").value(POHLAVI_2025))
                .andExpect(jsonPath("$.data[1].publisher").value("Digitální a informační agentura"))
                .andExpect(jsonPath("$.data[1].codeListNumber").value("151"))
                .andExpect(jsonPath("$.data[1].validFrom").value("2025-01-01"));
    }

    /** The search term must reach the service rather than being silently dropped. */
    @Test
    void list_passesTheQueryThrough() throws Exception {
        when(nkodCodelistService.list("pohl")).thenReturn(List.of(NkodCodelist.builder()
                .datasetIri(POHLAVI).codeListIri(POHLAVI_2025).title("Pohlaví").publisher("DIA").build()));

        mockMvc.perform(get("/api/codelist/nkod").param("q", "pohl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("Pohlaví"));
    }

    @Test
    void list_omitsUnsetOptionalFields() throws Exception {
        when(nkodCodelistService.list(null)).thenReturn(List.of(NkodCodelist.builder()
                .datasetIri(POHLAVI).codeListIri(POHLAVI_2025).title("Pohlaví").publisher("DIA").build()));

        mockMvc.perform(get("/api/codelist/nkod"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].description").doesNotExist())
                .andExpect(jsonPath("$.data[0].codeListNumber").doesNotExist())
                .andExpect(jsonPath("$.data[0].validFrom").doesNotExist());
    }

    @Test
    void list_emptyCatalogue_returnsAnEmptyList() throws Exception {
        when(nkodCodelistService.list(null)).thenReturn(List.of());

        mockMvc.perform(get("/api/codelist/nkod"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void list_nkodUnavailableAndNothingLoaded_returns503WithTheCzechMessage() throws Exception {
        when(nkodCodelistService.list(null)).thenThrow(new SparqlEndpointUnavailableException("NKOD", "down"));

        mockMvc.perform(get("/api/codelist/nkod"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("NKOD data nejsou momentálně dostupná."));
    }

    @Test
    void list_accessibleWithoutAuth() throws Exception {
        when(nkodCodelistService.list(null)).thenReturn(List.of());

        // No @WithMockSecurityUser — endpoint must be anonymous (public chain).
        mockMvc.perform(get("/api/codelist/nkod"))
                .andExpect(status().isOk());
    }
}
