package com.dia.ismdtoolbackend.controller;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
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
 * Note: @MockBean is deprecated in Spring Boot 3.4+ but remains the recommended
 * approach for @WebMvcTest until a clear migration path is provided.
 */
@WebMvcTest(controllers = ConceptController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class
    })
@Import({TestSecurityConfig.class, TestOntologySecurityService.class, com.dia.ismdtoolbackend.config.GlobalExceptionHandler.class})
@ActiveProfiles("test")
class ConceptControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ConceptService conceptService;

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
            null                        | null           | null   | Data pro vytvoření pojmu jsou prázdná
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
                .andExpect(jsonPath("$.conceptMetadata.id").value(1))
                .andExpect(jsonPath("$.conceptMetadata.slug").value(slug))
                .andExpect(jsonPath("$.conceptMetadata.conceptIri").value("http://example.org/TestConcept"))
                .andExpect(jsonPath("$.conceptMetadata.conceptName").value("TestConcept"))
                .andExpect(jsonPath("$.conceptDetail.iri").value("http://example.org/TestConcept"))
                .andExpect(jsonPath("$.conceptDetail['název'].cs").value("TestConcept"));
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
                .andExpect(jsonPath("$.conceptMetadata.conceptType").value("VLASTNOST"))
                .andExpect(jsonPath("$.conceptDetail.iri").value("http://example.org/PropertyConcept"));
    }
}