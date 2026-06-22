package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.GlobalExceptionHandler;
import com.dia.ismdtoolbackend.config.RppConfig;
import com.dia.ismdtoolbackend.config.security.TestOntologySecurityService;
import com.dia.ismdtoolbackend.config.security.TestSecurityConfig;
import com.dia.ismdtoolbackend.config.security.WithMockSecurityUser;
import com.dia.ismdtoolbackend.client.RppSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.RppSearchResultDto;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.service.RppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RppController.class,
        excludeAutoConfiguration = {
                org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class,
                org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
                org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration.class,
                org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration.class,
                org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration.class
        })
@Import({TestSecurityConfig.class, TestOntologySecurityService.class, GlobalExceptionHandler.class})
@ActiveProfiles("junit")
class RppControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RppService rppService;

    @MockitoBean
    private RppConfig rppConfig;

    @BeforeEach
    void setUp() {
        RppConfig.Search search = new RppConfig.Search();
        search.setDefaultLimit(20);
        search.setMaxLimit(50);
        when(rppConfig.getSearch()).thenReturn(search);
    }

    @Test
    void agendaSearchHappyPathReturnsEnvelope() throws Exception {
        when(rppService.searchAgendas(eq("spr"), eq(5)))
                .thenReturn(List.of(
                        new RppSearchResultDto("iri-1", "1", "Správa daní"),
                        new RppSearchResultDto("iri-2", "2", "Sprava něco")));

        mockMvc.perform(get("/api/rpp/agenda/search").param("q", "spr").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].iri").value("iri-1"))
                .andExpect(jsonPath("$.data[0].code").value("1"))
                .andExpect(jsonPath("$.data[0].nazev").value("Správa daní"))
                .andExpect(jsonPath("$.message").value("Vyhledávání agend úspěšně provedeno."));
    }

    @Test
    void isvsSearchForwardsAllParams() throws Exception {
        when(rppService.searchIsvs(any(), anyInt(), any()))
                .thenReturn(List.of(new RppSearchResultDto("iri-1", "1", "ISVS 1")));

        mockMvc.perform(get("/api/rpp/ais/search")
                        .param("q", "alfa")
                        .param("limit", "10")
                        .param("preferredAgendaCode", "A1382"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.message").value("Vyhledávání informačních systémů úspěšně provedeno."));

        ArgumentCaptor<String> qCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> limitCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> prefCap = ArgumentCaptor.forClass(String.class);
        verify(rppService).searchIsvs(qCap.capture(), limitCap.capture(), prefCap.capture());
        org.junit.jupiter.api.Assertions.assertEquals("alfa", qCap.getValue());
        org.junit.jupiter.api.Assertions.assertEquals(10, limitCap.getValue());
        org.junit.jupiter.api.Assertions.assertEquals("A1382", prefCap.getValue());
    }

    @Test
    void defaultLimitAppliedWhenMissing() throws Exception {
        when(rppService.searchAgendas(any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/rpp/agenda/search"))
                .andExpect(status().isOk());

        ArgumentCaptor<Integer> limitCap = ArgumentCaptor.forClass(Integer.class);
        verify(rppService).searchAgendas(any(), limitCap.capture());
        org.junit.jupiter.api.Assertions.assertEquals(20, limitCap.getValue());
    }

    @Test
    void limitZeroReturns400() throws Exception {
        mockMvc.perform(get("/api/rpp/agenda/search").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Parametr limit musí být v rozsahu 1 až 50."));
    }

    @Test
    void limitAboveMaxReturns400() throws Exception {
        mockMvc.perform(get("/api/rpp/agenda/search").param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void missingQueryForwardedAsNull() throws Exception {
        when(rppService.searchAgendas(any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/rpp/agenda/search").param("limit", "10"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> qCap = ArgumentCaptor.forClass(String.class);
        verify(rppService).searchAgendas(qCap.capture(), anyInt());
        org.junit.jupiter.api.Assertions.assertNull(qCap.getValue());
    }

    @Test
    void agendaSearchService503BubblesUp() throws Exception {
        when(rppService.searchAgendas(any(), anyInt()))
                .thenThrow(new SparqlEndpointUnavailableException(RppSparqlClient.RPP_LABEL,
                        "RPP agenda search fetch failed"));

        mockMvc.perform(get("/api/rpp/agenda/search").param("q", "spr"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("RPP data nejsou momentálně dostupná."));
    }

    @Test
    void isvsSearchService503BubblesUp() throws Exception {
        when(rppService.searchIsvs(any(), anyInt(), any()))
                .thenThrow(new SparqlEndpointUnavailableException(RppSparqlClient.RPP_LABEL,
                        "RPP ISVS search fetch failed"));

        mockMvc.perform(get("/api/rpp/ais/search").param("q", "alfa"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("RPP data nejsou momentálně dostupná."));
    }
}
