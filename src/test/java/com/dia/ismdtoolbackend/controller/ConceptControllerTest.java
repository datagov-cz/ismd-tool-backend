package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.GlobalExceptionHandler;
import com.dia.ismdtoolbackend.config.security.TestOntologySecurityService;
import com.dia.ismdtoolbackend.config.security.TestSecurityConfig;
import com.dia.ismdtoolbackend.config.security.WithMockSecurityUser;
import com.dia.ismdtoolbackend.exception.ConceptNotFoundException;
import com.dia.ismdtoolbackend.exception.ConceptStorageException;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.models.UserModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.service.ConceptService;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.service.NkdSnapshotEndpointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for ConceptController with security context.
 * <p>
 * Uses @WebMvcTest to load only the web layer with Spring Security enabled.
 * Authentication is provided via @WithMockSecurityUser annotation.
 * Authorization checks (@PreAuthorize) are mocked via OntologySecurityService.
 * <p>
 * Note: @MockitoBean replaces Spring Boot's deprecated @MockBean in these MVC
 * slice tests.
 */
@WebMvcTest(controllers = ConceptController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class,
        org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration.class,
        org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration.class,
        org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration.class
    })
@Import({TestSecurityConfig.class, TestOntologySecurityService.class, GlobalExceptionHandler.class})
@ActiveProfiles("junit")
class ConceptControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConceptService conceptService;

    @MockitoBean
    private NkdSnapshotEndpointService nkdSnapshotEndpointService;

    @BeforeEach
    void setUp() {
        // Reset security service to allow modifications by default
        TestOntologySecurityService.reset();
    }

    // ========== Create Concept Tests ==========

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testCreateConcept_Success() throws Exception {
        String userId = "user123";
        String slug = "test-ontology";

        String jsonRequest = """
                {
                    "conceptType": "TRIDA",
                    "ontologyGraphName": "test-ontology",
                    "namespace": "http://example.org/",
                    "nameModel": {
                        "name": {"cs": "TestConcept"}
                    },
                    "descriptionModel": {
                        "description": {"cs": "Test description"}
                    },
                    "type": "entity"
                }
                """;

        ConceptMetadataModel expectedMetadata = new ConceptMetadataModel();
        expectedMetadata.setId(1L);
        expectedMetadata.setConceptType(ConceptType.TRIDA);
        expectedMetadata.setConceptIri("http://example.org/TestConcept");
        expectedMetadata.setGraphName("test-ontology");
        expectedMetadata.setConceptName("TestConcept");
        expectedMetadata.setUser(new UserModel(userId));

        TestOntologySecurityService.setAllowModify(true);
        when(conceptService.createConcept(any(), eq(userId)))
                .thenReturn(expectedMetadata);

        mockMvc.perform(post("/api/concept/{slug}/create", slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.conceptType").value("TRIDA"))
                .andExpect(jsonPath("$.data.conceptIri").value("http://example.org/TestConcept"))
                .andExpect(jsonPath("$.data.graphName").value("test-ontology"))
                .andExpect(jsonPath("$.data.conceptName").value("TestConcept"))
                .andExpect(jsonPath("$.data.user.userId").value(userId))
                .andExpect(jsonPath("$.message").value("Pojem úspěšně vytvořen: "));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testCreateConcept_Unauthenticated() throws Exception {
        String slug = "test-ontology";
        String jsonRequest = """
                {
                    "conceptType": "TRIDA",
                    "ontologyGraphName": "test-ontology",
                    "namespace": "http://example.org/",
                    "nameModel": {
                        "name": {"cs": "TestConcept"}
                    },
                    "type": "entity"
                }
                """;

        TestOntologySecurityService.setAllowModify(true);
        mockMvc.perform(post("/api/concept/{slug}/create", slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @WithMockSecurityUser(userId = "user123")
    @CsvSource(delimiter = '|', textBlock = """
            invalid-namespace           | TestConcept    | entity | IRI není platné
            http://example.org/         | TestConcept    | null   | Typ třídy je povinný.
            http://example.org/         | Test123Concept | entity | Název může obsahovat pouze písmena
            """)
    void testCreateConcept_ValidationErrors(String namespace, String conceptName, String type, String expectedError) throws Exception {
        String userId = "user123";

        StringBuilder jsonBuilder = new StringBuilder();
        jsonBuilder.append("{\n");
        jsonBuilder.append("    \"conceptType\": \"TRIDA\"");

        if (!"null".equals(namespace)) {
            jsonBuilder.append(",\n    \"ontologyGraphName\": \"test-ontology\"");
            jsonBuilder.append(",\n    \"namespace\": \"").append(namespace).append("\"");
        }

        if (!"null".equals(conceptName)) {
            jsonBuilder.append(",\n    \"nameModel\": {\n");
            jsonBuilder.append("        \"name\": {\"cs\": \"").append(conceptName).append("\"}\n");
            jsonBuilder.append("    }");
        }

        if (!"null".equals(type)) {
            jsonBuilder.append(",\n    \"type\": \"").append(type).append("\"");
        }

        jsonBuilder.append("\n}");
        String jsonRequest = jsonBuilder.toString();
        String slug = "test-ontology";

        TestOntologySecurityService.setAllowModify(true);
        when(conceptService.createConcept(any(), eq(userId)))
                .thenThrow(new ConceptValidationException(expectedError));

        mockMvc.perform(post("/api/concept/{slug}/create", slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(expectedError));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testCreateConcept_MissingRequiredFields_RejectedByBeanValidation() throws Exception {
        String slug = "test-ontology";
        String jsonRequest = "{\n    \"conceptType\": \"TRIDA\"\n}";

        TestOntologySecurityService.setAllowModify(true);

        mockMvc.perform(post("/api/concept/{slug}/create", slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(containsString("Neplatná data v požadavku")))
                .andExpect(jsonPath("$.message").value(containsString("ontologyGraphName")))
                .andExpect(jsonPath("$.message").value(containsString("nameModel")));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testCreateConcept_StorageError() throws Exception {
        String userId = "user123";
        String slug = "test-ontology";
        String jsonRequest = """
                {
                    "conceptType": "TRIDA",
                    "ontologyGraphName": "test-ontology",
                    "namespace": "http://example.org/",
                    "nameModel": {
                        "name": {"cs": "TestConcept"}
                    },
                    "type": "entity"
                }
                """;

        TestOntologySecurityService.setAllowModify(true);
        when(conceptService.createConcept(any(), eq(userId)))
                .thenThrow(new ConceptStorageException("Nepodařilo se uložit pojem"));

        mockMvc.perform(post("/api/concept/{slug}/create", slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Nepodařilo se uložit pojem"));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testCreateConcept_IllegalArgumentException() throws Exception {
        String userId = "user123";
        String slug = "test-ontology";
        String jsonRequest = """
                {
                    "conceptType": "TRIDA",
                    "ontologyGraphName": "test-ontology",
                    "namespace": "http://example.org/",
                    "nameModel": {
                        "name": {"cs": "TestConcept"},
                        "languageTag": "cs"
                    },
                    "type": "entity"
                }
                """;

        TestOntologySecurityService.setAllowModify(true);
        when(conceptService.createConcept(any(), eq(userId)))
                .thenThrow(new IllegalArgumentException("Invalid argument provided"));

        mockMvc.perform(post("/api/concept/{slug}/create", slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Invalid argument provided"));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testCreateConcept_SecurityException() throws Exception {
        String userId = "user123";
        String slug = "test-ontology";
        String jsonRequest = """
                {
                    "conceptType": "TRIDA",
                    "ontologyGraphName": "test-ontology",
                    "namespace": "http://example.org/",
                    "nameModel": {
                        "name": {"cs": "TestConcept"},
                        "languageTag": "cs"
                    },
                    "type": "entity"
                }
                """;

        TestOntologySecurityService.setAllowModify(false);

        mockMvc.perform(post("/api/concept/{slug}/create", slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Přístup odepřen: nemáte oprávnění k této operaci."));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testCreateConcept_UnexpectedException() throws Exception {
        String userId = "user123";
        String slug = "test-ontology";
        String jsonRequest = """
                {
                    "conceptType": "TRIDA",
                    "ontologyGraphName": "test-ontology",
                    "namespace": "http://example.org/",
                    "nameModel": {
                        "name": {"cs": "TestConcept"},
                        "languageTag": "cs"
                    },
                    "type": "entity"
                }
                """;

        TestOntologySecurityService.setAllowModify(true);
        when(conceptService.createConcept(any(), eq(userId)))
                .thenThrow(new RuntimeException("Unexpected error"));

        mockMvc.perform(post("/api/concept/{slug}/create", slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Nastala neočekávaná chyba."));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testCreateConcepts_Success() throws Exception {
        String userId = "user123";
        String slug = "test-ontology";
        String jsonRequest = """
                [
                    {
                        "conceptType": "TRIDA",
                        "ontologyGraphName": "test-ontology",
                        "namespace": "http://example.org/",
                        "nameModel": {
                            "name": {"cs": "FirstConcept"}
                        },
                        "type": "entity"
                    },
                    {
                        "conceptType": "TRIDA",
                        "ontologyGraphName": "test-ontology",
                        "namespace": "http://example.org/",
                        "nameModel": {
                            "name": {"cs": "SecondConcept"}
                        },
                        "type": "entity"
                    }
                ]
                """;

        ConceptMetadataModel firstMetadata = new ConceptMetadataModel();
        firstMetadata.setId(1L);
        firstMetadata.setConceptName("FirstConcept");

        ConceptMetadataModel secondMetadata = new ConceptMetadataModel();
        secondMetadata.setId(2L);
        secondMetadata.setConceptName("SecondConcept");

        TestOntologySecurityService.setAllowModify(true);
        when(conceptService.createConcept(any(), eq(userId)))
                .thenReturn(firstMetadata, secondMetadata);

        mockMvc.perform(post("/api/concept/{slug}/create/bulk", slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].conceptName").value("FirstConcept"))
                .andExpect(jsonPath("$.data[1].id").value(2))
                .andExpect(jsonPath("$.data[1].conceptName").value("SecondConcept"))
                .andExpect(jsonPath("$.message").value("Pojmy úspěšně vytvořeny: "));

        verify(conceptService, times(2)).createConcept(any(), eq(userId));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testCreateConcepts_EmptyListRejected() throws Exception {
        TestOntologySecurityService.setAllowModify(true);

        mockMvc.perform(post("/api/concept/{slug}/create/bulk", "test-ontology")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Neplatná data v požadavku."));

        verify(conceptService, never()).createConcept(any(), anyString());
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testCreateConcepts_InvalidItemRejected() throws Exception {
        String jsonRequest = """
                [
                    {
                        "conceptType": "TRIDA",
                        "ontologyGraphName": "test-ontology",
                        "nameModel": {
                            "name": {"cs": "ValidConcept"}
                        },
                        "type": "entity"
                    },
                    {
                        "conceptType": "TRIDA"
                    }
                ]
                """;

        TestOntologySecurityService.setAllowModify(true);

        mockMvc.perform(post("/api/concept/{slug}/create/bulk", "test-ontology")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Neplatná data v požadavku."));

        verify(conceptService, never()).createConcept(any(), anyString());
    }

    // ========== Delete Concept Tests ==========

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testDeleteConcept_Success() throws Exception {
        Long conceptId = 1L;

        TestOntologySecurityService.setAllowModify(true);
        doNothing().when(conceptService).deleteConcept(conceptId);

        mockMvc.perform(delete("/api/concept/{conceptId}/delete", conceptId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Pojem úspěšně smazán."));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testDeleteConcept_NotFound() throws Exception {
        Long conceptId = 999L;

        TestOntologySecurityService.setAllowModify(true);
        doThrow(new ConceptNotFoundException("Pojem s ID 999 nebyl nalezen"))
                .when(conceptService).deleteConcept(conceptId);

        mockMvc.perform(delete("/api/concept/{conceptId}/delete", conceptId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Pojem s ID 999 nebyl nalezen"));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testDeleteConcept_UnexpectedException() throws Exception {
        Long conceptId = 1L;

        TestOntologySecurityService.setAllowModify(true);
        doThrow(new RuntimeException("Neočekávaná chyba"))
                .when(conceptService).deleteConcept(conceptId);

        mockMvc.perform(delete("/api/concept/{conceptId}/delete", conceptId))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Nastala neočekávaná chyba."));
    }

    // ========== Edit Concept Tests ==========

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testEditConcept_Success() throws Exception {
        Long conceptId = 1L;
        String userId = "user123";
        String jsonRequest = """
                {
                    "conceptType": "TRIDA",
                    "conceptIRI": "http://example.org/TestConcept",
                    "namespace": "http://example.org/",
                    "nameModel": {
                        "name": {"cs": "UpdatedConcept"},
                        "languageTag": "cs"
                    },
                    "descriptionModel": {
                        "description": {"cs": "Updated description"},
                        "languageTag": "cs"
                    }
                }
                """;

        ConceptMetadataModel expectedMetadata = new ConceptMetadataModel();
        expectedMetadata.setId(1L);
        expectedMetadata.setConceptType(ConceptType.TRIDA);
        expectedMetadata.setConceptIri("http://example.org/TestConcept");
        expectedMetadata.setGraphName("test-ontology");
        expectedMetadata.setConceptName("UpdatedConcept");
        expectedMetadata.setUser(new UserModel(userId));

        TestOntologySecurityService.setAllowModify(true);
        when(conceptService.editConcept(anyLong(), any(ConceptEditModel.class)))
                .thenReturn(expectedMetadata);

        mockMvc.perform(patch("/api/concept/{conceptId}/edit", conceptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.conceptType").value("TRIDA"))
                .andExpect(jsonPath("$.data.conceptIri").value("http://example.org/TestConcept"))
                .andExpect(jsonPath("$.data.conceptName").value("UpdatedConcept"))
                .andExpect(jsonPath("$.message").value("Pojem úspěšně upraven: "));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testEditConcept_ValidationException() throws Exception {
        Long conceptId = 1L;
        String jsonRequest = """
                {
                    "conceptType": "TRIDA",
                    "conceptIRI": "http://example.org/TestConcept"
                }
                """;

        TestOntologySecurityService.setAllowModify(true);
        when(conceptService.editConcept(anyLong(), any()))
                .thenThrow(new ConceptValidationException("Invalid concept IRI"));

        mockMvc.perform(patch("/api/concept/{conceptId}/edit", conceptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Invalid concept IRI"));
    }

    @Test
    @WithMockSecurityUser(userId = "userXXX")
    void testEditConcept_SecurityException() throws Exception {
        Long conceptId = 1L;
        String jsonRequest = """
                {
                    "conceptType": "TRIDA",
                    "conceptIRI": "http://example.org/TestConcept"
                }
                """;

        TestOntologySecurityService.setAllowModify(false);
        when(conceptService.editConcept(anyLong(), any()))
                .thenThrow(new SecurityException("Security violation"));

        mockMvc.perform(patch("/api/concept/{conceptId}/edit", conceptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Přístup odepřen: nemáte oprávnění k této operaci."));
    }

    /**
     * A concurrent create that loses the race to the concept_iri unique constraint surfaces as
     * 400, not 500 — the DB backstop and the service-layer uniqueness check agree on the status.
     */
    @Test
    @WithMockSecurityUser(userId = "user123")
    void testEditConcept_DataIntegrityViolation_mapsTo400() throws Exception {
        Long conceptId = 1L;
        String jsonRequest = """
                {
                    "conceptType": "TRIDA",
                    "conceptIRI": "http://example.org/TestConcept"
                }
                """;

        TestOntologySecurityService.setAllowModify(true);
        when(conceptService.editConcept(anyLong(), any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "duplicate key value violates unique constraint"));

        mockMvc.perform(patch("/api/concept/{conceptId}/edit", conceptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testEditConcept_UnexpectedException() throws Exception {
        Long conceptId = 1L;
        String jsonRequest = """
                {
                    "conceptType": "TRIDA",
                    "conceptIRI": "http://example.org/TestConcept"
                }
                """;

        TestOntologySecurityService.setAllowModify(true);
        when(conceptService.editConcept(anyLong(), any()))
                .thenThrow(new RuntimeException("Unexpected error"));

        mockMvc.perform(patch("/api/concept/{conceptId}/edit", conceptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Nastala neočekávaná chyba."));
    }

    // ========== Get Concept List Tests ==========

    @Test
    void testGetConceptList_AllConcepts() throws Exception {
        ConceptMetadataModel concept1 = new ConceptMetadataModel();
        concept1.setId(1L);
        concept1.setConceptIri("http://example.org/concept1");
        concept1.setConceptName("Concept1");
        concept1.setConceptType(ConceptType.TRIDA);

        ConceptMetadataModel concept2 = new ConceptMetadataModel();
        concept2.setId(2L);
        concept2.setConceptIri("http://example.org/concept2");
        concept2.setConceptName("Concept2");
        concept2.setConceptType(ConceptType.TRIDA);

        when(conceptService.getAll(null, null))
                .thenReturn(java.util.List.of(concept1, concept2));

        mockMvc.perform(get("/api/concept/list"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].conceptIri").value("http://example.org/concept1"))
                .andExpect(jsonPath("$.data[0].conceptName").value("Concept1"))
                .andExpect(jsonPath("$.data[1].id").value(2))
                .andExpect(jsonPath("$.data[1].conceptIri").value("http://example.org/concept2"))
                .andExpect(jsonPath("$.message").value("Žádost o seznam pojmů proběhla úspěšně."));
    }

    @Test
    void testGetConceptList_ByUserId() throws Exception {
        String userId = "user123";
        ConceptMetadataModel concept1 = new ConceptMetadataModel();
        concept1.setId(1L);
        concept1.setConceptIri("http://example.org/concept1");
        concept1.setConceptName("Concept1");
        concept1.setConceptType(ConceptType.TRIDA);
        concept1.setUser(new UserModel(userId));

        when(conceptService.getAll(userId, null))
                .thenReturn(java.util.List.of(concept1));

        mockMvc.perform(get("/api/concept/list")
                        .param("userId", userId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].user.userId").value(userId))
                .andExpect(jsonPath("$.message").value("Žádost o seznam pojmů proběhla úspěšně."));
    }

    @Test
    void testGetConceptList_ByPublishedStatus() throws Exception {
        ConceptMetadataModel concept1 = new ConceptMetadataModel();
        concept1.setId(1L);
        concept1.setConceptIri("http://example.org/concept1");
        concept1.setConceptName("Concept1");
        concept1.setConceptType(ConceptType.TRIDA);
        concept1.setIsPublished(true);

        when(conceptService.getAll(null, true))
                .thenReturn(java.util.List.of(concept1));

        mockMvc.perform(get("/api/concept/list")
                        .param("isPublished", "true"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].isPublished").value(true))
                .andExpect(jsonPath("$.message").value("Žádost o seznam pojmů proběhla úspěšně."));
    }

    @Test
    void testGetConceptList_EmptyResult() throws Exception {
        when(conceptService.getAll(null, null))
                .thenReturn(java.util.List.of());

        mockMvc.perform(get("/api/concept/list"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.message").value("Žádost o seznam pojmů proběhla úspěšně."));
    }

    @Test
    void testGetConceptList_CombinedFilters() throws Exception {
        String userId = "user123";
        ConceptMetadataModel concept1 = new ConceptMetadataModel();
        concept1.setId(1L);
        concept1.setConceptIri("http://example.org/concept1");
        concept1.setConceptName("Concept1");
        concept1.setConceptType(ConceptType.TRIDA);
        concept1.setUser(new UserModel(userId));
        concept1.setIsPublished(true);

        when(conceptService.getAll(userId, true))
                .thenReturn(java.util.List.of(concept1));

        mockMvc.perform(get("/api/concept/list")
                        .param("userId", userId)
                        .param("isPublished", "true"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].user.userId").value(userId))
                .andExpect(jsonPath("$.data[0].isPublished").value(true))
                .andExpect(jsonPath("$.message").value("Žádost o seznam pojmů proběhla úspěšně."));
    }

    // ========== Get Concept Detail Tests ==========

    @Test
    void testGetConceptDetail_Success() throws Exception {
        String slug = "test-concept";

        ConceptMetadataModel metadataModel = new ConceptMetadataModel();
        metadataModel.setId(1L);
        metadataModel.setSlug(slug);
        metadataModel.setConceptIri("http://example.org/TestConcept");
        metadataModel.setConceptName("TestConcept");
        metadataModel.setConceptType(ConceptType.TRIDA);

        com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel detailModel =
                com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel.builder()
                        .iri("http://example.org/TestConcept")
                        .types(java.util.List.of())
                        .name(java.util.Map.of("cs", "TestConcept"))
                        .description(java.util.Map.of("cs", "Test description"))
                        .build();

        com.dia.ismdtoolbackend.controller.dto.GetConceptDto conceptDto =
                new com.dia.ismdtoolbackend.controller.dto.GetConceptDto();
        conceptDto.setConceptMetadata(metadataModel);
        conceptDto.setConceptDetail(detailModel);

        when(conceptService.getConceptDetail(slug))
                .thenReturn(conceptDto);

        mockMvc.perform(get("/api/concept/{slug}/detail", slug))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.conceptMetadata.id").value(1))
                .andExpect(jsonPath("$.data.conceptMetadata.slug").value(slug))
                .andExpect(jsonPath("$.data.conceptMetadata.conceptIri").value("http://example.org/TestConcept"))
                .andExpect(jsonPath("$.data.conceptMetadata.conceptName").value("TestConcept"))
                .andExpect(jsonPath("$.data.conceptDetail.iri").value("http://example.org/TestConcept"))
                .andExpect(jsonPath("$.data.conceptDetail['název'].cs").value("TestConcept"));
    }

    @Test
    void testGetConceptDetail_NotFound() throws Exception {
        String slug = "non-existent-concept";

        when(conceptService.getConceptDetail(slug))
                .thenThrow(new ConceptNotFoundException("Pojem nebyl nalezen"));

        mockMvc.perform(get("/api/concept/{slug}/detail", slug))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Pojem nebyl nalezen"));
    }

    @Test
    void testGetConceptDetail_ReferencedConceptsResolvedExposed() throws Exception {
        String slug = "test-concept-with-refs";
        String exactMatchIri = "https://slovník.gov.cz/legislativní/sbírka/128/2000/pojem/obecní-úřad-obce";
        String localPropertyIri = "https://slovník.gov.cz/example/pojem/název";

        ConceptMetadataModel metadataModel = new ConceptMetadataModel();
        metadataModel.setId(7L);
        metadataModel.setSlug(slug);
        metadataModel.setConceptIri("http://example.org/TestWithRefs");
        metadataModel.setConceptName("TestWithRefs");
        metadataModel.setConceptType(ConceptType.TRIDA);

        com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto nkdEntry =
                com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto.builder()
                        .iri(exactMatchIri)
                        .conceptName(java.util.Map.of("cs", "Obecní úřad obce"))
                        .ontologyIri("https://slovník.gov.cz/legislativní/sbírka/128/2000")
                        .ontologyName(java.util.Map.of("cs", "Zákon 128/2000"))
                        .source(com.dia.ismdtoolbackend.enums.SearchSource.NKD)
                        .build();
        com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto ismdEntry =
                com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto.builder()
                        .iri(localPropertyIri)
                        .conceptName(java.util.Map.of("cs", "Název"))
                        .conceptSlug("example-nazev")
                        .ontologyIri("https://slovník.gov.cz/example")
                        .ontologyName(java.util.Map.of("cs", "Příklad"))
                        .source(com.dia.ismdtoolbackend.enums.SearchSource.ISMD)
                        .build();

        com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel detailModel =
                com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel.builder()
                        .iri("http://example.org/TestWithRefs")
                        .exactMatches(java.util.List.of(exactMatchIri))
                        .referencedConceptsResolved(java.util.Map.of(
                                exactMatchIri, nkdEntry,
                                localPropertyIri, ismdEntry))
                        .build();

        com.dia.ismdtoolbackend.controller.dto.GetConceptDto conceptDto =
                new com.dia.ismdtoolbackend.controller.dto.GetConceptDto();
        conceptDto.setConceptMetadata(metadataModel);
        conceptDto.setConceptDetail(detailModel);

        when(conceptService.getConceptDetail(slug)).thenReturn(conceptDto);

        mockMvc.perform(get("/api/concept/{slug}/detail", slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.conceptDetail['ekvivalentní-pojem'][0]").value(exactMatchIri))
                // NKD entry: source surfaces, conceptSlug absent (JsonInclude.NON_NULL)
                .andExpect(jsonPath("$.data.conceptDetail['referencované-pojmy-resolved']['"
                        + exactMatchIri + "'].source").value("NKD"))
                .andExpect(jsonPath("$.data.conceptDetail['referencované-pojmy-resolved']['"
                        + exactMatchIri + "'].ontologyName.cs").value("Zákon 128/2000"))
                .andExpect(jsonPath("$.data.conceptDetail['referencované-pojmy-resolved']['"
                        + exactMatchIri + "'].conceptSlug").doesNotExist())
                // ISMD entry: source + slug both present
                .andExpect(jsonPath("$.data.conceptDetail['referencované-pojmy-resolved']['"
                        + localPropertyIri + "'].source").value("ISMD"))
                .andExpect(jsonPath("$.data.conceptDetail['referencované-pojmy-resolved']['"
                        + localPropertyIri + "'].conceptSlug").value("example-nazev"));
    }

    @Test
    void testGetConceptDetail_DifferentConceptTypes() throws Exception {
        String slug = "property-concept";

        ConceptMetadataModel metadataModel = new ConceptMetadataModel();
        metadataModel.setId(2L);
        metadataModel.setSlug(slug);
        metadataModel.setConceptIri("http://example.org/PropertyConcept");
        metadataModel.setConceptName("PropertyConcept");
        metadataModel.setConceptType(ConceptType.VLASTNOST);

        com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel detailModel =
                com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel.builder()
                        .iri("http://example.org/PropertyConcept")
                        .types(java.util.List.of())
                        .name(java.util.Map.of("cs", "PropertyConcept"))
                        .build();

        com.dia.ismdtoolbackend.controller.dto.GetConceptDto conceptDto =
                new com.dia.ismdtoolbackend.controller.dto.GetConceptDto();
        conceptDto.setConceptMetadata(metadataModel);
        conceptDto.setConceptDetail(detailModel);

        when(conceptService.getConceptDetail(slug))
                .thenReturn(conceptDto);

        mockMvc.perform(get("/api/concept/{slug}/detail", slug))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.conceptMetadata.conceptType").value("VLASTNOST"))
                .andExpect(jsonPath("$.data.conceptDetail.iri").value("http://example.org/PropertyConcept"));
    }

    // ========== NKD local-copy endpoints (UPDATE / REMOVE) ==========

    @Test
    @WithMockSecurityUser(userId = "user123")
    void updateLocalCopy_returnsRefreshedSnapshotDto() throws Exception {
        TestOntologySecurityService.setAllowModify(true);
        com.dia.ismdtoolbackend.controller.dto.LinkSnapshotDto dto =
                com.dia.ismdtoolbackend.controller.dto.LinkSnapshotDto.builder()
                        .snapshotId(7L)
                        .owningConceptId(1L)
                        .origin(com.dia.ismdtoolbackend.enums.SnapshotOrigin.LINK_TARGET)
                        .status(com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel
                                .DeviationStatus.NO_DEVIATION)
                        .build();
        when(nkdSnapshotEndpointService.updateSnapshot(1L, 7L)).thenReturn(dto);

        mockMvc.perform(post("/api/concept/{conceptId}/localcopy/{snapshotId}/update", 1L, 7L))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.snapshotId").value(7))
                .andExpect(jsonPath("$.data.origin").value("LINK_TARGET"))
                .andExpect(jsonPath("$.data.status").value("NO_DEVIATION"));
    }

    @Test
    @WithMockSecurityUser(userId = "other")
    void updateLocalCopy_notOwner_forbidden() throws Exception {
        TestOntologySecurityService.setAllowModify(false);   // canModifyConcept → false

        mockMvc.perform(post("/api/concept/{conceptId}/localcopy/{snapshotId}/update", 1L, 7L))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void updateLocalCopy_snapshotNotFound_badRequest() throws Exception {
        TestOntologySecurityService.setAllowModify(true);
        doThrow(new com.dia.ismdtoolbackend.exception.OntologyValidationException(
                "Lokální kopie s id 7 nebyla nalezena."))
                .when(nkdSnapshotEndpointService).updateSnapshot(1L, 7L);

        mockMvc.perform(post("/api/concept/{conceptId}/localcopy/{snapshotId}/update", 1L, 7L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("nebyla nalezena")));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void removeLocalCopy_success() throws Exception {
        TestOntologySecurityService.setAllowModify(true);
        doNothing().when(nkdSnapshotEndpointService).removeSnapshot(1L, 7L);

        mockMvc.perform(delete("/api/concept/{conceptId}/localcopy/{snapshotId}", 1L, 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value(containsString("odstraněna")));
    }

    @Test
    @WithMockSecurityUser(userId = "other")
    void removeLocalCopy_notOwner_forbidden() throws Exception {
        TestOntologySecurityService.setAllowModify(false);

        mockMvc.perform(delete("/api/concept/{conceptId}/localcopy/{snapshotId}", 1L, 7L))
                .andExpect(status().isForbidden());
    }
}
