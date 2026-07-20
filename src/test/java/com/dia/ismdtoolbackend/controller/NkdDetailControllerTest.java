package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.security.TestSecurityConfig;
import com.dia.ismdtoolbackend.controller.dto.GetNkdConceptDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyListDto;
import com.dia.ismdtoolbackend.controller.dto.NkdOntologyListItemDto;
import com.dia.ismdtoolbackend.exception.NkdEndpointException;
import com.dia.ismdtoolbackend.exception.NkdResourceNotFoundException;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.service.NkdDetailService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = NkdDetailController.class,
        excludeAutoConfiguration = {
                org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class,
                org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
                org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration.class,
                org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration.class,
                org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration.class
        })
@Import({TestSecurityConfig.class, com.dia.ismdtoolbackend.config.GlobalExceptionHandler.class})
@ActiveProfiles("junit")
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

    // ── List by IRIs ───────────────────────────────────────────────────

    @Test
    void getOntologyList_success_returns200() throws Exception {
        GetNkdOntologyListDto dto = new GetNkdOntologyListDto(List.of(), null, 2, null);
        when(nkdDetailService.getOntologyList(any())).thenReturn(dto);

        mockMvc.perform(get("/api/nkd/ontology/list")
                        .param("iris", ONTOLOGY_IRI, "https://example.org/ontology/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void getOntologyList_missingIris_returns400() throws Exception {
        mockMvc.perform(get("/api/nkd/ontology/list"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getOntologyList_upstreamUnavailable_returns503() throws Exception {
        when(nkdDetailService.getOntologyList(any()))
                .thenThrow(new NkdEndpointException("NKD SPARQL endpoint je nedostupný."));

        mockMvc.perform(get("/api/nkd/ontology/list").param("iris", ONTOLOGY_IRI))
                .andExpect(status().isServiceUnavailable());
    }

    // ── List all (paginated) ───────────────────────────────────────────

    @Test
    void listAllOntologies_defaultsApplied_returns200() throws Exception {
        GetNkdOntologyListDto dto = new GetNkdOntologyListDto(List.of(), 0, 0, 0);
        when(nkdDetailService.listAllOntologies(anyInt(), anyInt(), anyString())).thenReturn(dto);

        mockMvc.perform(get("/api/nkd/ontology/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<Integer> limitCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> offsetCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> langCap = ArgumentCaptor.forClass(String.class);
        verify(nkdDetailService).listAllOntologies(limitCap.capture(), offsetCap.capture(), langCap.capture());
        Assertions.assertEquals(20, limitCap.getValue());
        Assertions.assertEquals(0, offsetCap.getValue());
        Assertions.assertEquals("cs", langCap.getValue());
    }

    @Test
    void listAllOntologies_explicitParams_forwardedToService() throws Exception {
        GetNkdOntologyListDto dto = new GetNkdOntologyListDto(List.of(), 100, 100, 5000);
        when(nkdDetailService.listAllOntologies(eq(50), eq(100), eq("en"))).thenReturn(dto);

        mockMvc.perform(get("/api/nkd/ontology/all")
                        .param("limit", "50")
                        .param("offset", "100")
                        .param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.celkový-počet").value(100));
    }

    @Test
    void listAllOntologies_upstreamUnavailable_returns503() throws Exception {
        when(nkdDetailService.listAllOntologies(anyInt(), anyInt(), anyString()))
                .thenThrow(new NkdEndpointException("NKD SPARQL endpoint je nedostupný."));

        mockMvc.perform(get("/api/nkd/ontology/all"))
                .andExpect(status().isServiceUnavailable());
    }

    // ── Download ───────────────────────────────────────────────────────

    @Test
    void downloadOntology_ttlDefault_returnsTurtleAttachment() throws Exception {
        byte[] body = "<x> <y> <z> .".getBytes();
        when(nkdDetailService.downloadOntology(eq(ONTOLOGY_IRI), eq("ttl"))).thenReturn(body);

        MvcResult result = mockMvc.perform(get("/api/nkd/ontology/download").param("iri", ONTOLOGY_IRI))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/turtle"))
                .andExpect(header().string("Content-Disposition",
                        Matchers.containsString("attachment; filename=\"")))
                .andExpect(header().string("Content-Disposition",
                        Matchers.endsWith(".ttl\"")))
                .andReturn();
        Assertions.assertArrayEquals(body,
                result.getResponse().getContentAsByteArray());
    }

    @Test
    void downloadOntology_jsonLdFormat_returnsLdJsonAttachment() throws Exception {
        byte[] body = "{\"@context\":{}}".getBytes();
        when(nkdDetailService.downloadOntology(eq(ONTOLOGY_IRI), eq("json-ld"))).thenReturn(body);

        mockMvc.perform(get("/api/nkd/ontology/download")
                        .param("iri", ONTOLOGY_IRI)
                        .param("format", "json-ld"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/ld+json"))
                .andExpect(header().string("Content-Disposition",
                        Matchers.endsWith(".jsonld\"")));
    }

    @Test
    void downloadOntology_jsonLdMixedCase_alsoMatches() throws Exception {
        // Confirms equalsIgnoreCase branch in the contentType + extension switches.
        when(nkdDetailService.downloadOntology(eq(ONTOLOGY_IRI), eq("JSON-LD"))).thenReturn(new byte[]{1, 2});

        mockMvc.perform(get("/api/nkd/ontology/download")
                        .param("iri", ONTOLOGY_IRI)
                        .param("format", "JSON-LD"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/ld+json"))
                .andExpect(header().string("Content-Disposition",
                        Matchers.endsWith(".jsonld\"")));
    }

    @Test
    void downloadOntology_iriWithLastSegment_usesSegmentAsFilename() throws Exception {
        String iri = "https://example.org/ontology/my-slug";
        when(nkdDetailService.downloadOntology(eq(iri), eq("ttl"))).thenReturn(new byte[]{0});

        mockMvc.perform(get("/api/nkd/ontology/download").param("iri", iri))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        Matchers.containsString("my-slug.ttl")));
    }

    @Test
    void downloadOntology_iriWithQueryString_stripsQueryFromFilename() throws Exception {
        String iri = "https://example.org/ontology/slug?v=2";
        when(nkdDetailService.downloadOntology(eq(iri), eq("ttl"))).thenReturn(new byte[]{0});

        mockMvc.perform(get("/api/nkd/ontology/download").param("iri", iri))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        Matchers.containsString("slug.ttl")))
                .andExpect(header().string("Content-Disposition",
                        Matchers.not(Matchers.containsString("v=2"))));
    }

    @Test
    void downloadOntology_iriWithFragment_stripsFragmentFromFilename() throws Exception {
        String iri = "https://example.org/ontology/slug#main";
        when(nkdDetailService.downloadOntology(eq(iri), eq("ttl"))).thenReturn(new byte[]{0});

        mockMvc.perform(get("/api/nkd/ontology/download").param("iri", iri))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        Matchers.containsString("slug.ttl")))
                .andExpect(header().string("Content-Disposition",
                        Matchers.not(Matchers.containsString("main"))));
    }

    @Test
    void downloadOntology_iriEndsWithSlash_fallsBackToOntologyFilename() throws Exception {
        String iri = "https://example.org/ontology/";
        when(nkdDetailService.downloadOntology(eq(iri), eq("ttl"))).thenReturn(new byte[]{0});

        // Last char is slash, so slash >= length-1 → fallback to "ontology".
        mockMvc.perform(get("/api/nkd/ontology/download").param("iri", iri))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        Matchers.containsString("ontology.ttl")));
    }

    @Test
    void downloadOntology_iriWithoutSlash_fallsBackToOntologyFilename() throws Exception {
        String iri = "urn-no-slash-iri";
        when(nkdDetailService.downloadOntology(eq(iri), eq("ttl"))).thenReturn(new byte[]{0});

        // No '/' → slash < 0 → fallback to "ontology".
        mockMvc.perform(get("/api/nkd/ontology/download").param("iri", iri))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        Matchers.containsString("ontology.ttl")));
    }

    @Test
    void downloadOntology_iriWithUnicodeSegment_isUrlEncoded() throws Exception {
        String iri = "https://example.org/ontology/příklad";
        when(nkdDetailService.downloadOntology(eq(iri), eq("ttl"))).thenReturn(new byte[]{0});

        // URLEncoder encodes UTF-8 bytes, '+' replaced with %20 in the controller.
        // Just confirm the ASCII suffix and no raw non-ASCII characters in the header.
        MvcResult res = mockMvc.perform(get("/api/nkd/ontology/download").param("iri", iri))
                .andExpect(status().isOk())
                .andReturn();
        String disposition = res.getResponse().getHeader("Content-Disposition");
        Assertions.assertNotNull(disposition);
        Assertions.assertTrue(disposition.endsWith(".ttl\""),
                "Disposition should end with .ttl: " + disposition);
        Assertions.assertTrue(disposition.contains("%"),
                "Disposition should contain percent-encoded segment: " + disposition);
    }

    @Test
    void downloadOntology_upstreamUnavailable_returns503() throws Exception {
        when(nkdDetailService.downloadOntology(anyString(), anyString()))
                .thenThrow(new NkdEndpointException("NKD SPARQL endpoint je nedostupný."));

        mockMvc.perform(get("/api/nkd/ontology/download").param("iri", ONTOLOGY_IRI))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void downloadOntology_notFound_returns404() throws Exception {
        when(nkdDetailService.downloadOntology(anyString(), anyString()))
                .thenThrow(new NkdResourceNotFoundException("Slovník nenalezen."));

        mockMvc.perform(get("/api/nkd/ontology/download").param("iri", ONTOLOGY_IRI))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadOntology_missingIri_returns400() throws Exception {
        mockMvc.perform(get("/api/nkd/ontology/download"))
                .andExpect(status().isBadRequest());
    }

    // Suppress IDE complaints about an "unused" item-DTO import we keep for symmetry
    // with other list-endpoint tests in the file.
    @SuppressWarnings("unused")
    private NkdOntologyListItemDto unusedSymmetryReference;
}
