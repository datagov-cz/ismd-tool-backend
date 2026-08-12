package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.GlobalExceptionHandler;
import com.dia.ismdtoolbackend.config.security.TestOntologySecurityService;
import com.dia.ismdtoolbackend.config.security.TestSecurityConfig;
import com.dia.ismdtoolbackend.config.security.WithMockSecurityUser;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramSummaryDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.MaterializeResultDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.PositionDto;
import com.dia.ismdtoolbackend.exception.DiagramReadbackFailedException;
import com.dia.ismdtoolbackend.service.DiagramService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
                .thenReturn(new DiagramDto("pracovni-pomer", 0L, null, List.of(), List.of(), 0));

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

    /**
     * The node id travels in the BODY, not the path — a real {@code iri:https://…/pojem/…} contains slashes
     * that no path segment can carry (Tomcat rejects {@code %2F}). Uses a full slash-bearing IRI on purpose:
     * a slash-free placeholder would pass regardless and prove nothing.
     */
    @Test
    @WithMockSecurityUser(userId = "user123")
    void stageOverlay_nodeIdWithSlashesTravelsInBody() throws Exception {
        String nodeId = "iri:https://slovník.gov.cz/a124---datový-slovník-iskn/pojem/budova-je-umístěna-na-parcele";
        when(diagramService.stageOverlay(eq("pracovni-pomer"), eq(nodeId), any()))
                .thenReturn(new DiagramDto.Node("n1", "relationNode",
                        new PositionDto(0.0, 0.0), null, false, null));

        mockMvc.perform(patch("/api/diagram/pracovni-pomer/nodes/overlay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nodeId": "%s",
                                 "domain": "https://slovník.gov.cz/a124---datový-slovník-iskn/pojem/parcela",
                                 "range": "https://slovník.gov.cz/a124---datový-slovník-iskn/pojem/budova"}
                                """.formatted(nodeId)))
                .andExpect(status().isOk());
    }

    /** {@code nodeId} is mandatory — addressing, not content. */
    @Test
    @WithMockSecurityUser(userId = "user123")
    void stageOverlay_rejectsMissingNodeId() throws Exception {
        mockMvc.perform(patch("/api/diagram/pracovni-pomer/nodes/overlay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\": \"https://x/A\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- version on the wire --------------------------------------------------------------------
    // DiagramOverlayVersionIntegrationTest pins the version the SERVICE computes; these pin that it
    // survives serialization — that the FE can actually read it off the PATCH response, and that it does
    // NOT appear per-node in the fat read (where the version belongs to the enclosing diagram).

    private static final String OVERLAY_BODY = """
            {"nodeId": "iri:https://x/pojem/a", "broaderConcept": ["https://x/pojem/b"]}
            """;

    /**
     * The reviewer's finding, at the wire level: the stage response must expose the advanced version so the
     * client can echo it into its next {@code PUT …/layout} without re-reading the whole diagram.
     */
    @Test
    @WithMockSecurityUser(userId = "user123")
    void stageOverlay_responseSerializesVersion() throws Exception {
        when(diagramService.stageOverlay(eq("pracovni-pomer"), any(), any()))
                .thenReturn(new DiagramDto.Node("iri:https://x/pojem/a", "classNode",
                        new PositionDto(10.0, 20.0), null, false, null, 8L));

        mockMvc.perform(patch("/api/diagram/pracovni-pomer/nodes/overlay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OVERLAY_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(8))
                .andExpect(jsonPath("$.data.id").value("iri:https://x/pojem/a"));
    }

    /**
     * The readback contract on the wire: the write committed, so the client must be told to RELOAD, not
     * retry. 502 + a stable code + the post-write version is what makes that actionable — a blind retry
     * would send the stale version and earn a spurious 409.
     */
    @Test
    @WithMockSecurityUser(userId = "user123")
    void stageOverlay_readbackFailure_returns502WithCodeAndVersion() throws Exception {
        when(diagramService.stageOverlay(eq("pracovni-pomer"), any(), any()))
                .thenThrow(new DiagramReadbackFailedException(9L, new RuntimeException("fuseki down")));

        mockMvc.perform(patch("/api/diagram/pracovni-pomer/nodes/overlay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OVERLAY_BODY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("DIAGRAM_SAVED_READBACK_FAILED"))
                .andExpect(jsonPath("$.data.version").value(9));
    }

    /** Same contract on the layout save. */
    @Test
    @WithMockSecurityUser(userId = "user123")
    void saveLayout_readbackFailure_returns502WithCodeAndVersion() throws Exception {
        when(diagramService.saveLayout(eq("pracovni-pomer"), any()))
                .thenThrow(new DiagramReadbackFailedException(4L, new RuntimeException("fuseki down")));

        mockMvc.perform(put("/api/diagram/pracovni-pomer/layout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 3, \"nodes\": [], \"edges\": []}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("DIAGRAM_SAVED_READBACK_FAILED"))
                .andExpect(jsonPath("$.data.version").value(4));
    }

    /**
     * {@code @JsonInclude(NON_NULL)} is what keeps the field off the in-diagram form. A node built without a
     * version must omit the key entirely rather than emit {@code "version": null}, which a client could read
     * as "this node has no version" instead of "version lives on the diagram".
     */
    @Test
    @WithMockSecurityUser(userId = "user123")
    void getDetail_nodesOmitVersion_diagramCarriesIt() throws Exception {
        DiagramDto.Node node = new DiagramDto.Node("iri:https://x/pojem/a", "classNode",
                new PositionDto(0.0, 0.0), null, true, null);     // in-diagram form: no version
        when(diagramService.getDiagram(eq("pracovni-pomer")))
                .thenReturn(new DiagramDto("pracovni-pomer", 7L, null, List.of(node), List.of(), 0));

        String body = mockMvc.perform(get("/api/diagram/pracovni-pomer/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(7))
                .andExpect(jsonPath("$.data.nodes[0].id").value("iri:https://x/pojem/a"))
                // collapsed is a primitive, so NON_NULL never suppresses it — it must always be on the wire.
                .andExpect(jsonPath("$.data.nodes[0].collapsed").value(true))
                .andReturn().getResponse().getContentAsString();

        // Asserted on the raw body, NOT with jsonPath(...).doesNotExist(): JsonPath resolves an explicit
        // `"version": null` to absent, so that matcher passes even when the key IS serialized — which is the
        // one thing this test has to rule out. Only the raw text distinguishes omitted from present-and-null.
        JsonNode serialized = new ObjectMapper().readTree(body).path("data").path("nodes").get(0);
        assertThat(serialized.has("version"))
                .as("nodes inside the fat read omit the version key entirely; body was: %s", body)
                .isFalse();
    }
}
