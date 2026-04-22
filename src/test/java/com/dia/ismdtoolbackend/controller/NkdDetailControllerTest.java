package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.security.TestSecurityConfig;
import com.dia.ismdtoolbackend.controller.dto.GetNkdConceptDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyDto;
import com.dia.ismdtoolbackend.exception.NkdEndpointException;
import com.dia.ismdtoolbackend.exception.NkdResourceNotFoundException;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.service.NkdDetailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = NkdDetailController.class,
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
                org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration.class
        })
@Import({TestSecurityConfig.class, com.dia.ismdtoolbackend.config.GlobalExceptionHandler.class})
@ActiveProfiles("test")
class NkdDetailControllerTest {

    private static final String ONTOLOGY_IRI = "https://example.org/ontology/1";
    private static final String CONCEPT_IRI = "https://example.org/concept/1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NkdDetailService nkdDetailService;

    // ── Ontology ───────────────────────────────────────────────────────

    @Test
    void getOntologyDetail_success_returns200() throws Exception {
        OntologyDetailModel model = OntologyDetailModel.builder().iri(ONTOLOGY_IRI).build();
        when(nkdDetailService.getOntologyDetail(ONTOLOGY_IRI))
                .thenReturn(new GetNkdOntologyDto(model));

        mockMvc.perform(get("/api/nkd/ontology/detail").param("iri", ONTOLOGY_IRI))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ontologyDetail.iri").value(ONTOLOGY_IRI));
    }

    @Test
    void getOntologyDetail_urlEncodedIri_passesDecodedIriToService() throws Exception {
        OntologyDetailModel model = OntologyDetailModel.builder().iri(ONTOLOGY_IRI).build();
        when(nkdDetailService.getOntologyDetail(anyString()))
                .thenReturn(new GetNkdOntologyDto(model));

        // Hit the endpoint with a pre-built URI containing a real URL-encoded query string
        // so Spring performs actual query-param decoding (MockMvc's .param() and URI-template
        // overloads both bypass decoding).
        java.net.URI uri = java.net.URI.create(
                "/api/nkd/ontology/detail?iri=https%3A%2F%2Fexample.org%2Fontology%2Fwith%20space");
        mockMvc.perform(get(uri))
                .andExpect(status().isOk());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(nkdDetailService).getOntologyDetail(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue())
                .isEqualTo("https://example.org/ontology/with space");
    }

    @Test
    void getOntologyDetail_notFound_returns404() throws Exception {
        when(nkdDetailService.getOntologyDetail(anyString()))
                .thenThrow(new NkdResourceNotFoundException("Slovník nenalezen."));

        mockMvc.perform(get("/api/nkd/ontology/detail").param("iri", ONTOLOGY_IRI))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void getOntologyDetail_upstreamUnavailable_returns503() throws Exception {
        when(nkdDetailService.getOntologyDetail(anyString()))
                .thenThrow(new NkdEndpointException("NKD SPARQL endpoint je nedostupný."));

        mockMvc.perform(get("/api/nkd/ontology/detail").param("iri", ONTOLOGY_IRI))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void getOntologyDetail_missingIri_returns400() throws Exception {
        mockMvc.perform(get("/api/nkd/ontology/detail"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void getOntologyDetail_blankIri_returns400() throws Exception {
        when(nkdDetailService.getOntologyDetail(anyString()))
                .thenThrow(new IllegalArgumentException("IRI nesmí být prázdné."));

        mockMvc.perform(get("/api/nkd/ontology/detail").param("iri", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void getOntologyDetail_unauthenticated_allowed() throws Exception {
        OntologyDetailModel model = OntologyDetailModel.builder().iri(ONTOLOGY_IRI).build();
        when(nkdDetailService.getOntologyDetail(anyString()))
                .thenReturn(new GetNkdOntologyDto(model));

        mockMvc.perform(get("/api/nkd/ontology/detail").param("iri", ONTOLOGY_IRI))
                .andExpect(status().isOk());
    }

    // ── Concept ────────────────────────────────────────────────────────

    @Test
    void getConceptDetail_success_returns200() throws Exception {
        OntologyDetailModel.ConceptDetailModel model =
                OntologyDetailModel.ConceptDetailModel.builder().iri(CONCEPT_IRI).build();
        when(nkdDetailService.getConceptDetail(eq(CONCEPT_IRI), eq(ONTOLOGY_IRI)))
                .thenReturn(new GetNkdConceptDto(model, ONTOLOGY_IRI));

        mockMvc.perform(get("/api/nkd/concept/detail")
                        .param("iri", CONCEPT_IRI)
                        .param("ontologyIri", ONTOLOGY_IRI))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.conceptDetail.iri").value(CONCEPT_IRI))
                .andExpect(jsonPath("$.data.ontologyIri").value(ONTOLOGY_IRI));
    }

    @Test
    void getConceptDetail_withoutOntologyIri_returns200() throws Exception {
        OntologyDetailModel.ConceptDetailModel model =
                OntologyDetailModel.ConceptDetailModel.builder().iri(CONCEPT_IRI).build();
        when(nkdDetailService.getConceptDetail(eq(CONCEPT_IRI), any()))
                .thenReturn(new GetNkdConceptDto(model, null));

        mockMvc.perform(get("/api/nkd/concept/detail").param("iri", CONCEPT_IRI))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conceptDetail.iri").value(CONCEPT_IRI));
    }

    @Test
    void getConceptDetail_notFound_returns404() throws Exception {
        when(nkdDetailService.getConceptDetail(anyString(), any()))
                .thenThrow(new NkdResourceNotFoundException("Pojem nenalezen."));

        mockMvc.perform(get("/api/nkd/concept/detail").param("iri", CONCEPT_IRI))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void getConceptDetail_upstreamUnavailable_returns503() throws Exception {
        when(nkdDetailService.getConceptDetail(anyString(), any()))
                .thenThrow(new NkdEndpointException("NKD SPARQL endpoint je nedostupný."));

        mockMvc.perform(get("/api/nkd/concept/detail").param("iri", CONCEPT_IRI))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void getConceptDetail_missingIri_returns400() throws Exception {
        mockMvc.perform(get("/api/nkd/concept/detail"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getConceptDetail_blankIri_returns400() throws Exception {
        when(nkdDetailService.getConceptDetail(anyString(), any()))
                .thenThrow(new IllegalArgumentException("IRI nesmí být prázdné."));

        mockMvc.perform(get("/api/nkd/concept/detail").param("iri", "   "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getConceptDetail_unauthenticated_allowed() throws Exception {
        OntologyDetailModel.ConceptDetailModel model =
                OntologyDetailModel.ConceptDetailModel.builder().iri(CONCEPT_IRI).build();
        when(nkdDetailService.getConceptDetail(anyString(), any()))
                .thenReturn(new GetNkdConceptDto(model, null));

        mockMvc.perform(get("/api/nkd/concept/detail").param("iri", CONCEPT_IRI))
                .andExpect(status().isOk());
    }
}
