package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.models.UserModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.service.ConceptService;
import com.dia.ismdtoolbackend.enums.ConceptType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ConceptControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ConceptService conceptService;

    @InjectMocks
    private ConceptController conceptController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(conceptController).build();
    }
    /*

    // ========== Create Concept Tests ==========

    @Test
    void testCreateConcept_Success() throws Exception {
        String userId = "user123";

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

        when(conceptService.createConcept(any(ClassConceptModel.class), eq(userId)))
                .thenReturn(expectedMetadata);

        mockMvc.perform(post("/api/concept/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest)
                        .param("userId", userId))
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
    void testCreateConcept_EmptyUserId() throws Exception {
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

        mockMvc.perform(post("/api/concept/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest)
                        .param("userId", ""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("ID uživatele je povinné."));
    }

    @Test
    void testCreateConcept_NullUserId() throws Exception {
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

        mockMvc.perform(post("/api/concept/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            invalid-namespace           | TestConcept    | entity | IRI není platné
            http://example.org/         | TestConcept    | null   | Typ třídy je povinný.
            null                        | null           | null   | Data pro vytvoření pojmu jsou prázdná
            http://example.org/         | Test123Concept | entity | Název může obsahovat pouze písmena
            """)
    void testCreateConcept_ValidationErrors(String namespace, String conceptName, String type, String expectedError) throws Exception {
        String userId = "user123";

        // Build JSON request dynamically based on parameters
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

        when(conceptService.createConcept(any(), eq(userId)))
                .thenThrow(new org.apache.jena.ontology.OntologyException(expectedError));

        mockMvc.perform(post("/api/concept/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest)
                        .param("userId", userId))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(expectedError));
    }

    @Test
    void testCreateConcept_StorageError() throws Exception {
        String userId = "user123";
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

        when(conceptService.createConcept(any(), eq(userId)))
                .thenThrow(new org.apache.jena.ontology.OntologyException("Nepodařilo se uložit pojem"));

        mockMvc.perform(post("/api/concept/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest)
                        .param("userId", userId))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Nepodařilo se uložit pojem"));
    }

    @Test
    void testCreateConcept_IllegalArgumentException() throws Exception {
        String userId = "user123";
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

        when(conceptService.createConcept(any(), eq(userId)))
                .thenThrow(new IllegalArgumentException("Invalid argument provided"));

        mockMvc.perform(post("/api/concept/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest)
                        .param("userId", userId))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Invalid argument provided"));
    }

    @Test
    void testCreateConcept_SecurityException() throws Exception {
        String userId = "user123";
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

        when(conceptService.createConcept(any(), eq(userId)))
                .thenThrow(new SecurityException("Security violation"));

        mockMvc.perform(post("/api/concept/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest)
                        .param("userId", userId))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Security violation"));
    }

    @Test
    void testCreateConcept_UnexpectedException() throws Exception {
        String userId = "user123";
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

        when(conceptService.createConcept(any(), eq(userId)))
                .thenThrow(new RuntimeException("Unexpected error"));

        mockMvc.perform(post("/api/concept/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest)
                        .param("userId", userId))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Nastala neočekávaná chyba při vytváření pojmu."));
    }

    // ========== Delete Concept Tests ==========

    @Test
    void testDeleteConcept_Success() throws Exception {
        Long conceptId = 1L;

        doNothing().when(conceptService).deleteConcept(conceptId);

        mockMvc.perform(delete("/api/concept/{conceptId}/delete", conceptId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Pojem úspěšně smazán."));
    }

    @Test
    void testDeleteConcept_NotFound() throws Exception {
        Long conceptId = 999L;

        doThrow(new org.apache.jena.ontology.OntologyException("Pojem s ID 999 nebyl nalezen"))
                .when(conceptService).deleteConcept(conceptId);

        mockMvc.perform(delete("/api/concept/{conceptId}/delete", conceptId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Pojem s ID 999 nebyl nalezen"));
    }

    @Test
    void testDeleteConcept_OntologyException() throws Exception {
        Long conceptId = 1L;

        doThrow(new org.apache.jena.ontology.OntologyException("Chyba při mazání pojmu"))
                .when(conceptService).deleteConcept(conceptId);

        mockMvc.perform(delete("/api/concept/{conceptId}/delete", conceptId))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Chyba při mazání pojmu"));
    }

    @Test
    void testDeleteConcept_UnexpectedException() throws Exception {
        Long conceptId = 1L;

        doThrow(new RuntimeException("Neočekávaná chyba"))
                .when(conceptService).deleteConcept(conceptId);

        mockMvc.perform(delete("/api/concept/{conceptId}/delete", conceptId))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Nastala neočekávaná chyba při mazání pojmu."));
    }

    // ========== Edit Concept Tests ==========

    @Test
    void testEditConcept_Success() throws Exception {
        String userId = "user123";
        String jsonRequest = """
                {
                    "conceptType": "TRIDA",
                    "conceptIRI": "http://example.org/TestConcept",
                    "namespace": "http://example.org/",
                    "nameModel": {
                        "name": {"cs": "UpdatedConcept"}
                    },
                    "descriptionModel": {
                        "description": {"cs": "Updated description"}
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

        when(conceptService.editConcept(any(ConceptEditModel.class)))
                .thenReturn(expectedMetadata);

        mockMvc.perform(patch("/api/concept/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest)
                        .param("userId", userId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.conceptType").value("TRIDA"))
                .andExpect(jsonPath("$.data.conceptIri").value("http://example.org/TestConcept"))
                .andExpect(jsonPath("$.data.conceptName").value("UpdatedConcept"))
                .andExpect(jsonPath("$.message").value("Pojem úspěšně upraven: "));
    }

    @Test
    void testEditConcept_EmptyUserId() throws Exception {
        String jsonRequest = """
                {
                    "conceptType": "TRIDA",
                    "conceptIRI": "http://example.org/TestConcept"
                }
                """;

        mockMvc.perform(patch("/api/concept/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest)
                        .param("userId", ""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("ID uživatele je povinné."));
    }

    @Test
    void testEditConcept_NullUserId() throws Exception {
        String jsonRequest = """
                {
                    "conceptType": "TRIDA",
                    "conceptIRI": "http://example.org/TestConcept"
                }
                """;

        mockMvc.perform(patch("/api/concept/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testEditConcept_IllegalArgumentException() throws Exception {
        String userId = "user123";
        String jsonRequest = """
                {
                    "conceptType": "TRIDA",
                    "conceptIRI": "http://example.org/TestConcept"
                }
                """;

        when(conceptService.editConcept(any()))
                .thenThrow(new IllegalArgumentException("Invalid concept IRI"));

        mockMvc.perform(patch("/api/concept/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest)
                        .param("userId", userId))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Invalid concept IRI"));
    }

    @Test
    void testEditConcept_SecurityException() throws Exception {
        String userId = "user123";
        String jsonRequest = """
                {
                    "conceptType": "TRIDA",
                    "conceptIRI": "http://example.org/TestConcept"
                }
                """;

        when(conceptService.editConcept(any()))
                .thenThrow(new SecurityException("Security violation"));

        mockMvc.perform(patch("/api/concept/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest)
                        .param("userId", userId))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Security violation"));
    }

    @Test
    void testEditConcept_UnexpectedException() throws Exception {
        String userId = "user123";
        String jsonRequest = """
                {
                    "conceptType": "TRIDA",
                    "conceptIRI": "http://example.org/TestConcept"
                }
                """;

        when(conceptService.editConcept(any()))
                .thenThrow(new RuntimeException("Unexpected error"));

        mockMvc.perform(patch("/api/concept/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest)
                        .param("userId", userId))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Nastala neočekávaná chyba při úpravě pojmu."));
    }
    
     */
}