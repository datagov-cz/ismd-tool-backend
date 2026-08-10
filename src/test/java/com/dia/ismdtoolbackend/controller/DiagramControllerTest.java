package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.GlobalExceptionHandler;
import com.dia.ismdtoolbackend.config.security.TestOntologySecurityService;
import com.dia.ismdtoolbackend.config.security.TestSecurityConfig;
import com.dia.ismdtoolbackend.config.security.WithMockSecurityUser;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramSummaryDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.MaterializeResultDto;
import com.dia.ismdtoolbackend.service.DiagramService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer contract for the diagram controller: {@code GET /all} lists summaries; reads are open to any
 * authenticated user (the deliberate {@code canViewResource()} posture — a non-owner can read); writes are
 * ownership-gated ({@code belongsToUserBySlug}) and 403 for a non-owner. Service is mocked — this exercises
 * routing + authz, not the fat-read join.
 */
@WebMvcTest(controllers = DiagramController.class,
        excludeAutoConfiguration = {
                org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class,
                org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
                org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration.class,
                org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration.class,
                org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration.class
        })
@Import({TestSecurityConfig.class, TestOntologySecurityService.class, GlobalExceptionHandler.class})
@ActiveProfiles("junit")
class DiagramControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private DiagramService diagramService;

    @BeforeEach
    void setUp() {
        TestOntologySecurityService.reset();   // allowModify = true (owner) by default
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void getAll_returnsDiagramSummaries() throws Exception {
        when(diagramService.listAll()).thenReturn(List.of(
                new DiagramSummaryDto("pracovni-pomer", "pracovni-pomer",
                        "https://x/pracovni-pomer", 7, "2026-07-21T10:00:00")));

        mockMvc.perform(get("/api/diagram/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].ontologySlug").value("pracovni-pomer"))
                .andExpect(jsonPath("$.data[0].nodeCount").value(7));
    }
    
    @Test
    @WithMockSecurityUser(userId = "not-the-owner")
    void getDetail_readableByNonOwner() throws Exception {
        TestOntologySecurityService.setAllowModify(false);   // not the owner → writes would 403
        when(diagramService.getDiagram(eq("pracovni-pomer")))
                .thenReturn(new DiagramDto("pracovni-pomer", null, List.of(), List.of(), 0));

        // canViewResource() (any authenticated) gates the read, NOT belongsToUserBySlug → still 200.
        mockMvc.perform(get("/api/diagram/pracovni-pomer/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ontologySlug").value("pracovni-pomer"));
    }

    // Writes are ownership-gated — a non-owner is denied before the handler runs.
    @Test
    @WithMockSecurityUser(userId = "not-the-owner")
    void materialize_forbiddenForNonOwner() throws Exception {
        TestOntologySecurityService.setAllowModify(false);   // belongsToUserBySlug → false

        mockMvc.perform(post("/api/diagram/pracovni-pomer/materialize"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void materialize_allowedForOwner() throws Exception {
        when(diagramService.materialize(eq("pracovni-pomer")))
                .thenReturn(new MaterializeResultDto(List.of(), List.of(), List.of()));

        mockMvc.perform(post("/api/diagram/pracovni-pomer/materialize"))
                .andExpect(status().isOk());
    }
}
