package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.GlobalExceptionHandler;
import com.dia.ismdtoolbackend.config.security.TestOntologySecurityService;
import com.dia.ismdtoolbackend.config.security.TestSecurityConfig;
import com.dia.ismdtoolbackend.config.security.WithMockSecurityUser;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramSummaryDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.MaterializeResultDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.PositionDto;
import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;
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
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
                .thenReturn(new DiagramDto("pracovni-pomer", 0L, null, List.of(), List.of(), List.of()));

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
     * A concept IRI travels in the overlay entry's BODY, never a path segment — a real
     * {@code iri:https://…/pojem/…} contains slashes that no path segment can carry (Tomcat rejects
     * {@code %2F}). Uses a full slash-bearing IRI on purpose: a slash-free placeholder would pass
     * regardless and prove nothing.
     */
    @Test
    @WithMockSecurityUser(userId = "user123")
    void saveLayout_overlayConceptIriWithSlashesTravelsInBody() throws Exception {
        String conceptIri = "iri:https://slovník.gov.cz/a124---datový-slovník-iskn/pojem/budova-je-umístěna-na-parcele";
        when(diagramService.saveLayout(eq("pracovni-pomer"), any()))
                .thenReturn(new DiagramDto("pracovni-pomer", 1L, null, List.of(), List.of(), List.of()));

        mockMvc.perform(put("/api/diagram/pracovni-pomer/layout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version": 1, "nodes": [], "overlays": [
                                  {"conceptIri": "%s",
                                   "domain": "https://slovník.gov.cz/a124---datový-slovník-iskn/pojem/parcela",
                                   "range": "https://slovník.gov.cz/a124---datový-slovník-iskn/pojem/budova"}]}
                                """.formatted(conceptIri)))
                .andExpect(status().isOk());

        ArgumentCaptor<DiagramLayoutDto> captor = ArgumentCaptor.forClass(DiagramLayoutDto.class);
        verify(diagramService).saveLayout(eq("pracovni-pomer"), captor.capture());
        assertThat(captor.getValue().overlays()).singleElement()
                .satisfies(o -> assertThat(o.conceptIri()).isEqualTo(conceptIri));
    }

    /** {@code conceptIri} is mandatory on an overlay entry — addressing, not content. */
    @Test
    @WithMockSecurityUser(userId = "user123")
    void saveLayout_overlayRejectsMissingConceptIri() throws Exception {
        mockMvc.perform(put("/api/diagram/pracovni-pomer/layout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 1, \"nodes\": [], \"overlays\": [{\"domain\": \"https://x/A\"}]}"))
                .andExpect(status().isBadRequest());
        verify(diagramService, never()).saveLayout(any(), any());
    }

    /** Omitting overlays entirely is the FE's ordinary autosave shape, and must bind without a 400. */
    @Test
    @WithMockSecurityUser(userId = "user123")
    void saveLayout_omittedOverlays_isAccepted() throws Exception {
        when(diagramService.saveLayout(eq("pracovni-pomer"), any()))
                .thenReturn(new DiagramDto("pracovni-pomer", 1L, null, List.of(), List.of(), List.of()));

        mockMvc.perform(put("/api/diagram/pracovni-pomer/layout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 1, \"nodes\": []}"))
                .andExpect(status().isOk());

        ArgumentCaptor<DiagramLayoutDto> captor = ArgumentCaptor.forClass(DiagramLayoutDto.class);
        verify(diagramService).saveLayout(eq("pracovni-pomer"), captor.capture());
        assertThat(captor.getValue().overlays())
                .as("an omitted overlays array reaches the service as null, meaning 'do not touch'")
                .isNull();
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
     * {@code version} and {@code nodes} are mandatory, so a generated client declares them and a missing one
     * is a 400 naming the field — not a save that silently succeeds now and 409s as a phantom version
     * conflict on the next write, nor one that wipes the canvas.
     */
    @Test
    @WithMockSecurityUser(userId = "user123")
    void saveLayout_missingRequiredField_returns400() throws Exception {
        String noVersion = "{\"nodes\": [], \"edges\": []}";
        String noNodes = "{\"version\": 3, \"edges\": []}";

        for (String body : List.of(noVersion, noNodes)) {
            mockMvc.perform(put("/api/diagram/pracovni-pomer/layout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(diagramService, never()).saveLayout(any(), any());
    }

    /**
     * The FE keeps ONE edge model — the fat read shape — and echoes it back on Save. The derived fields
     * ({@code source}/{@code target}/{@code type}/{@code data}) are ignored rather than rejected, so the
     * client never has to strip them into a separate write type. They are ignored, never trusted: endpoints
     * are re-projected from {@code live ⊕ overlay} on read, so a stale or hand-edited endpoint here cannot
     * contradict the ontology.
     */
    @Test
    @WithMockSecurityUser(userId = "user123")
    void saveLayout_acceptsTheFatReadEdgeShape_ignoringDerivedFields() throws Exception {
        when(diagramService.saveLayout(eq("pracovni-pomer"), any()))
                .thenReturn(new DiagramDto("pracovni-pomer", 4L, null, List.of(), List.of(), List.of()));

        String fatEdge = """
                {"version": 3, "nodes": [],
                 "edges": [{
                   "id": "https://x/pojem/rel",
                   "source": "iri:https://x/pojem/a",
                   "target": "iri:https://x/pojem/b",
                   "type": "relationEdge",
                   "segments": [{"x": 12.5, "y": -4}],
                   "data": {"edgeKind": "VZTAH", "pending": false,
                            "iri": "https://x/pojem/rel", "label": {"cs": "vztah"}}
                 }]}
                """;

        mockMvc.perform(put("/api/diagram/pracovni-pomer/layout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fatEdge))
                .andExpect(status().isOk());

        ArgumentCaptor<DiagramLayoutDto> captor = ArgumentCaptor.forClass(DiagramLayoutDto.class);
        verify(diagramService).saveLayout(eq("pracovni-pomer"), captor.capture());
        assertThat(captor.getValue().edges()).singleElement().satisfies(e -> {
            assertThat(e.id()).isEqualTo("https://x/pojem/rel");
            assertThat(e.segments()).containsExactly(new EdgeWaypoint(12.5, -4));
        });
    }

    /**
     * {@code edges} is optional: a freshly auto-laid-out canvas has positioned every node but has no
     * hand-routed edge to report, and must not be forced to send an empty array to save its layout.
     */
    @Test
    @WithMockSecurityUser(userId = "user123")
    void saveLayout_omittedEdges_isAccepted() throws Exception {
        when(diagramService.saveLayout(eq("pracovni-pomer"), any()))
                .thenReturn(new DiagramDto("pracovni-pomer", 4L, null, List.of(), List.of(), List.of()));

        mockMvc.perform(put("/api/diagram/pracovni-pomer/layout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 3, \"nodes\": []}"))
                .andExpect(status().isOk());
    }

    /**
     * The op-6 marker deletes the VZTAH, so an endpoint-less marker would destroy a concept without adding
     * the hierarchy link meant to replace it. Both endpoints are rejected at the edge.
     */
    @Test
    @WithMockSecurityUser(userId = "user123")
    void saveLayout_convertToHierarchyMissingEndpoint_returns400() throws Exception {
        String noBroader = """
                {"version":1,"nodes":[],"overlays":[
                  {"conceptIri":"https://x/pojem/rel","convertToHierarchy":{"addBroaderOn":"https://x/pojem/a"}}]}
                """;
        String noTarget = """
                {"version":1,"nodes":[],"overlays":[
                  {"conceptIri":"https://x/pojem/rel","convertToHierarchy":{"broader":"https://x/pojem/b"}}]}
                """;

        for (String body : List.of(noBroader, noTarget)) {
            mockMvc.perform(put("/api/diagram/pracovni-pomer/layout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(diagramService, never()).saveLayout(any(), any());
    }

    /**
     * A node's optional flags are absent from a real client's save body, and a client that tracks them may
     * send an explicit {@code null}. Neither form is a malformed request: both must bind, defaulting
     * {@code collapsed} to false, rather than 400 out of the message converter.
     */
    @Test
    @WithMockSecurityUser(userId = "user123")
    void saveLayout_nodeFlagsAbsentOrNull_bindWithoutParseError() throws Exception {
        when(diagramService.saveLayout(eq("pracovni-pomer"), any()))
                .thenReturn(new DiagramDto("pracovni-pomer", 1L, null,
                        List.of(), List.of(), List.of()));

        String absent = """
                {"version":1,"nodes":[{"id":"https://x/pojem/a","position":{"x":0,"y":16.5}}],
                 "edges":[{"id":"e1","source":"https://x/pojem/a","target":"https://x/pojem/b",
                           "edgeKind":"SUBCLASS_OF","sourceHandle":"https://x/pojem/a",
                           "targetHandle":"https://x/pojem/b"}]}
                """;
        String explicitNull = """
                {"version":1,"nodes":[{"id":"https://x/pojem/a","position":{"x":0,"y":16.5},
                           "parentId":null,"collapsed":null}],"edges":[]}
                """;

        for (String body : List.of(absent, explicitNull)) {
            mockMvc.perform(put("/api/diagram/pracovni-pomer/layout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }
    }

    /**
     * The version belongs to the diagram, never to a node. {@code DiagramDto.Node} carries no version field
     * at all, so this pins that none leaks onto the wire — a {@code "version"} key on a node would read as a
     * per-node lock that does not exist.
     */
    @Test
    @WithMockSecurityUser(userId = "user123")
    void getDetail_nodesOmitVersion_diagramCarriesIt() throws Exception {
        DiagramDto.Node node = new DiagramDto.Node("iri:https://x/pojem/a", "classNode",
                new PositionDto(0.0, 0.0), null, true, null);     // in-diagram form: no version
        when(diagramService.getDiagram(eq("pracovni-pomer")))
                .thenReturn(new DiagramDto("pracovni-pomer", 7L, null, List.of(node), List.of(), List.of()));

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
