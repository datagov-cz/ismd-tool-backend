package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.GlobalExceptionHandler;
import com.dia.ismdtoolbackend.config.RppConfig;
import com.dia.ismdtoolbackend.config.security.TestOntologySecurityService;
import com.dia.ismdtoolbackend.config.security.TestSecurityConfig;
import com.dia.ismdtoolbackend.config.security.WithMockSecurityUser;
import com.dia.ismdtoolbackend.controller.dto.RppSearchResultDto;
import com.dia.ismdtoolbackend.service.RppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
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
                org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
                org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration.class
        })
@Import({TestSecurityConfig.class, TestOntologySecurityService.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
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
    @WithMockSecurityUser(userId = "user123")
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
    @WithMockSecurityUser(userId = "user123")
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
    @WithMockSecurityUser(userId = "user123")
    void defaultLimitAppliedWhenMissing() throws Exception {
        when(rppService.searchAgendas(any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/rpp/agenda/search"))
                .andExpect(status().isOk());

        ArgumentCaptor<Integer> limitCap = ArgumentCaptor.forClass(Integer.class);
        verify(rppService).searchAgendas(any(), limitCap.capture());
        org.junit.jupiter.api.Assertions.assertEquals(20, limitCap.getValue());
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void limitZeroReturns400() throws Exception {
        mockMvc.perform(get("/api/rpp/agenda/search").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Parametr limit musí být v rozsahu 1 až 50."));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void limitAboveMaxReturns400() throws Exception {
        mockMvc.perform(get("/api/rpp/agenda/search").param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void unauthenticatedReturns403() throws Exception {
        mockMvc.perform(get("/api/rpp/agenda/search"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void missingQueryForwardedAsNull() throws Exception {
        when(rppService.searchAgendas(any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/rpp/agenda/search").param("limit", "10"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> qCap = ArgumentCaptor.forClass(String.class);
        verify(rppService).searchAgendas(qCap.capture(), anyInt());
        org.junit.jupiter.api.Assertions.assertNull(qCap.getValue());
    }
}
