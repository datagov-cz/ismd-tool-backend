package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.client.ValidationClient;
import com.dia.ismdtoolbackend.config.ValidationConfig;
import com.dia.ismdtoolbackend.config.security.TestOntologySecurityService;
import com.dia.ismdtoolbackend.config.security.TestSecurityConfig;
import com.dia.ismdtoolbackend.controller.dto.ResolveConceptsRequest;
import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.service.NkdDetailService;
import com.dia.ismdtoolbackend.service.OntologyDownloadService;
import com.dia.ismdtoolbackend.service.OntologyService;
import com.dia.ismdtoolbackend.service.OntologyUploadService;
import com.dia.ismdtoolbackend.service.ValidationService;
import com.dia.ismdtoolbackend.service.impl.ConceptMetadataResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvc-slice integration coverage for {@code POST /api/ontology/concepts/resolve}.
 * <p>
 * Focus: HTTP contract (JSON shape, status codes), {@code @Valid} enforcement
 * via {@link com.dia.ismdtoolbackend.config.GlobalExceptionHandler}, and
 * propagation of upstream SPARQL failures to HTTP 503. The resolver itself is
 * mocked — orchestration logic is covered by {@code ConceptMetadataResolverTest}.
 * <p>
 * Kept in its own file so the 1000+ line {@link OntologyControllerTest} stays
 * focused on the upload/CRUD surface.
 */
@WebMvcTest(controllers = OntologyController.class,
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
                org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration.class
        })
@Import({TestSecurityConfig.class, TestOntologySecurityService.class, com.dia.ismdtoolbackend.config.GlobalExceptionHandler.class})
@ActiveProfiles("junit")
class OntologyControllerResolveTest {

    private static final String ISMD_IRI = "https://data.gov.cz/zdroj/slovnik/local/pojem/a";
    private static final String NKD_IRI = "https://slovník.gov.cz/datový/sportovní/pojem/sport";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private OntologyUploadService ontologyUploadService;
    @MockitoBean private OntologyService ontologyService;
    @MockitoBean private OntologyDownloadService ontologyDownloadService;
    @MockitoBean private ValidationService validationService;
    @MockitoBean private ValidationClient validationClient;
    @MockitoBean private ValidationConfig validationConfig;
    @MockitoBean private NkdDetailService nkdDetailService;
    @MockitoBean private ConceptMetadataResolver conceptMetadataResolver;
    @MockitoBean private com.dia.ismdtoolbackend.service.snapshot.NkdSnapshotWarmer nkdSnapshotWarmer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Happy path ────────────────────────────────────────────────────────

    @Test
    void resolveConcepts_returnsAllFieldsInDtoShape() throws Exception {
        ResolvedConceptDto ismdResolved = ResolvedConceptDto.builder()
                .iri(ISMD_IRI)
                .conceptName(Map.of("cs", "Lokální pojem"))
                .conceptSlug("lokalni-pojem")
                .ontologyIri("https://data.gov.cz/zdroj/slovnik/local")
                .ontologyName(Map.of("cs", "Lokální slovník"))
                .source(SearchSource.ISMD)
                .build();
        ResolvedConceptDto nkdResolved = ResolvedConceptDto.builder()
                .iri(NKD_IRI)
                .conceptName(Map.of("cs", "NKD pojem"))
                .ontologyIri("https://slovník.gov.cz/datový/sportovní")
                .ontologyName(Map.of("cs", "Sportovní slovník"))
                .source(SearchSource.NKD)
                .build();
        when(conceptMetadataResolver.resolveAll(anyList()))
                .thenReturn(Map.of(ISMD_IRI, ismdResolved, NKD_IRI, nkdResolved));

        ResolveConceptsRequest request = new ResolveConceptsRequest(List.of(ISMD_IRI, NKD_IRI));

        mockMvc.perform(post("/api/ontology/concepts/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.resolved." + jsonPathKey(ISMD_IRI) + ".conceptName.cs").value("Lokální pojem"))
                .andExpect(jsonPath("$.data.resolved." + jsonPathKey(ISMD_IRI) + ".conceptSlug").value("lokalni-pojem"))
                .andExpect(jsonPath("$.data.resolved." + jsonPathKey(ISMD_IRI) + ".ontologyName.cs").value("Lokální slovník"))
                .andExpect(jsonPath("$.data.resolved." + jsonPathKey(ISMD_IRI) + ".source").value("ISMD"))
                .andExpect(jsonPath("$.data.resolved." + jsonPathKey(NKD_IRI) + ".source").value("NKD"))
                // NKD concept must NOT have conceptSlug (JsonInclude.NON_NULL on the DTO)
                .andExpect(jsonPath("$.data.resolved." + jsonPathKey(NKD_IRI) + ".conceptSlug").doesNotExist());
    }

    @Test
    void resolveConcepts_unresolvedIrisAbsentFromResponse() throws Exception {
        when(conceptMetadataResolver.resolveAll(anyList())).thenReturn(Map.of());

        ResolveConceptsRequest request = new ResolveConceptsRequest(List.of(ISMD_IRI));

        mockMvc.perform(post("/api/ontology/concepts/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.resolved").isMap())
                .andExpect(jsonPath("$.data.resolved." + jsonPathKey(ISMD_IRI)).doesNotExist());
    }

    // ── Validation (400) ──────────────────────────────────────────────────

    @Test
    void resolveConcepts_emptyList_returns400() throws Exception {
        // @NotEmpty on iris
        String body = "{\"iris\": []}";

        mockMvc.perform(post("/api/ontology/concepts/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Neplatná data")));
    }

    @Test
    void resolveConcepts_blankIri_returns400() throws Exception {
        // @NotBlank on the list element
        String body = "{\"iris\": [\"\"]}";

        mockMvc.perform(post("/api/ontology/concepts/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void resolveConcepts_missingBody_returns400() throws Exception {
        mockMvc.perform(post("/api/ontology/concepts/resolve")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void resolveConcepts_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/ontology/concepts/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── Error propagation (503) ───────────────────────────────────────────

    @Test
    void resolveConcepts_ismdUnavailable_returns503WithLocalizedMessage() throws Exception {
        when(conceptMetadataResolver.resolveAll(anyList()))
                .thenThrow(new SparqlEndpointUnavailableException("ISMD", "Fuseki connection refused"));

        ResolveConceptsRequest request = new ResolveConceptsRequest(List.of(ISMD_IRI));

        mockMvc.perform(post("/api/ontology/concepts/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                // GlobalExceptionHandler formats as "<label> data nejsou momentálně dostupná."
                .andExpect(jsonPath("$.message").value("ISMD data nejsou momentálně dostupná."));
    }

    @Test
    void resolveConcepts_unexpectedRuntimeException_returns500() throws Exception {
        when(conceptMetadataResolver.resolveAll(anyList()))
                .thenThrow(new RuntimeException("boom"));

        ResolveConceptsRequest request = new ResolveConceptsRequest(List.of(ISMD_IRI));

        mockMvc.perform(post("/api/ontology/concepts/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── Public endpoint (no auth header) ──────────────────────────────────

    @Test
    void resolveConcepts_publicEndpoint_noAuthRequired() throws Exception {
        when(conceptMetadataResolver.resolveAll(anyList())).thenReturn(Map.of());

        ResolveConceptsRequest request = new ResolveConceptsRequest(List.of(ISMD_IRI));

        // No @WithMockSecurityUser — anonymous request must not be rejected as 401/403
        mockMvc.perform(post("/api/ontology/concepts/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    /**
     * Encodes an IRI for inclusion in a JsonPath expression — dots and other
     * separators inside the IRI key would otherwise be interpreted as nested
     * paths. We wrap the literal in {@code ['...']} (JsonPath bracket notation).
     */
    private static String jsonPathKey(String iri) {
        return "['" + iri + "']";
    }
}
