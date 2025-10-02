package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.models.UserModel;
import com.dia.ismdtoolbackend.service.OntologyService;
import com.dia.ismdtoolbackend.service.OntologyUploadService;
import org.apache.jena.riot.Lang;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class OntologyControllerTest {

    private MockMvc mockMvc;

    @Mock
    private OntologyUploadService ontologyUploadService;

    @Mock
    private OntologyService ontologyService;

    @InjectMocks
    private OntologyController ontologyController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(ontologyController).build();
    }

    @Test
    void testUploadFromFile_Success() throws Exception {
        String userId = "user123";
        String providedName = "test-ontology";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.ttl",
                "text/turtle",
                "@prefix owl: <http://www.w3.org/2002/07/owl#> . <http://example.org/test> a owl:Ontology .".getBytes()
        );

        OntologyMetadataModel expectedMetadata = new OntologyMetadataModel();
        expectedMetadata.setGraphName(providedName);
        expectedMetadata.setUser(new UserModel(userId));

        when(ontologyUploadService.determineRDFFormat(any())).thenReturn(Lang.TURTLE);
        when(ontologyUploadService.uploadFromFile(any(), eq(providedName), eq(Lang.TURTLE), eq(userId)))
                .thenReturn(expectedMetadata);

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(file)
                        .param("providedName", providedName)
                        .param("userId", userId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.graphName").value(providedName))
                .andExpect(jsonPath("$.data.user.userId").value(userId))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void testUploadFromFile_SuccessWithoutProvidedName() throws Exception {
        String userId = "user123";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.ttl",
                "text/turtle",
                "@prefix owl: <http://www.w3.org/2002/07/owl#> . <http://example.org/test> a owl:Ontology .".getBytes()
        );

        OntologyMetadataModel expectedMetadata = new OntologyMetadataModel();
        expectedMetadata.setGraphName("generated-graph-name");
        expectedMetadata.setUser(new UserModel(userId));

        when(ontologyUploadService.determineRDFFormat(any())).thenReturn(Lang.TURTLE);
        when(ontologyUploadService.uploadFromFile(any(), isNull(), eq(Lang.TURTLE), eq(userId)))
                .thenReturn(expectedMetadata);

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(file)
                        .param("userId", userId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.graphName").value("generated-graph-name"))
                .andExpect(jsonPath("$.data.user.userId").value(userId))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void testUploadFromFile_EmptyFile() throws Exception {
        String userId = "user123";
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file",
                "empty.ttl",
                "text/turtle",
                new byte[0]
        );

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(emptyFile)
                        .param("userId", userId))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Soubor je prázdný."));
    }

    @Test
    void testUploadFromFile_UnsupportedRDFFormat() throws Exception {
        String userId = "user123";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.unknown",
                "application/unknown",
                "some content".getBytes()
        );

        when(ontologyUploadService.determineRDFFormat(any())).thenReturn(null);

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(file)
                        .param("userId", userId))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("RDF jazyk není podporován."));
    }

    @Test
    void testUploadFromFile_ServiceException() throws Exception {
        String userId = "user123";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.ttl",
                "text/turtle",
                "invalid rdf content".getBytes()
        );

        when(ontologyUploadService.determineRDFFormat(any())).thenReturn(Lang.TURTLE);
        when(ontologyUploadService.uploadFromFile(any(), any(), eq(Lang.TURTLE), eq(userId)))
                .thenThrow(new RuntimeException("Parse error"));

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(file)
                        .param("userId", userId))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Nastala neočekávaná chyba při nahrávání slovníku."));
    }

    @Test
    void testUploadFromFile_MissingUserId() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.ttl",
                "text/turtle",
                "@prefix owl: <http://www.w3.org/2002/07/owl#> . <http://example.org/test> a owl:Ontology .".getBytes()
        );

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testUploadFromFile_MissingFile() throws Exception {
        String userId = "user123";

        mockMvc.perform(multipart("/api/ontology/upload")
                        .param("userId", userId))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testUploadFromFile_JsonLdFormat() throws Exception {
        String userId = "user123";
        String providedName = "jsonld-ontology";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.jsonld",
                "application/ld+json",
                "{\"@context\":{\"owl\":\"http://www.w3.org/2002/07/owl#\"},\"@type\":\"owl:Ontology\"}".getBytes()
        );

        OntologyMetadataModel expectedMetadata = new OntologyMetadataModel();
        expectedMetadata.setGraphName(providedName);
        expectedMetadata.setUser(new UserModel(userId));

        when(ontologyUploadService.determineRDFFormat(any())).thenReturn(Lang.JSONLD);
        when(ontologyUploadService.uploadFromFile(any(), eq(providedName), eq(Lang.JSONLD), eq(userId)))
                .thenReturn(expectedMetadata);

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(file)
                        .param("providedName", providedName)
                        .param("userId", userId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.graphName").value(providedName))
                .andExpect(jsonPath("$.data.user.userId").value(userId));
    }

    @Test
    void testUploadFromFile_LargeFile() throws Exception {
        String userId = "user123";
        StringBuilder largeContent = new StringBuilder();
        largeContent.append("@prefix owl: <http://www.w3.org/2002/07/owl#> .");
        largeContent.append("<http://example.org/test> a owl:Ontology .");

        for (int i = 0; i < 1000; i++) {
            largeContent.append(String.format("<http://example.org/entity%d> a owl:Class .", i));
        }

        MockMultipartFile largeFile = new MockMultipartFile(
                "file",
                "large.ttl",
                "text/turtle",
                largeContent.toString().getBytes()
        );

        OntologyMetadataModel expectedMetadata = new OntologyMetadataModel();
        expectedMetadata.setGraphName("large-ontology");
        expectedMetadata.setUser(new UserModel(userId));

        when(ontologyUploadService.determineRDFFormat(any())).thenReturn(Lang.TURTLE);
        when(ontologyUploadService.uploadFromFile(any(), isNull(), eq(Lang.TURTLE), eq(userId)))
                .thenReturn(expectedMetadata);

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(largeFile)
                        .param("userId", userId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.graphName").value("large-ontology"))
                .andExpect(jsonPath("$.data.user.userId").value(userId));
    }

    @Test
    void testUploadFromFile_AlreadyExistsScenario() throws Exception {
        String userId = "user123";
        String providedName = "existing-ontology";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.ttl",
                "text/turtle",
                "@prefix owl: <http://www.w3.org/2002/07/owl#> . <http://example.org/test> a owl:Ontology .".getBytes()
        );

        OntologyMetadataModel existingMetadata = new OntologyMetadataModel();
        existingMetadata.setId(1L);
        existingMetadata.setGraphName(providedName);
        existingMetadata.setUser(new UserModel(userId));

        when(ontologyUploadService.determineRDFFormat(any())).thenReturn(Lang.TURTLE);
        when(ontologyUploadService.uploadFromFile(any(), eq(providedName), eq(Lang.TURTLE), eq(userId)))
                .thenReturn(existingMetadata);

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(file)
                        .param("providedName", providedName)
                        .param("userId", userId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.graphName").value(providedName))
                .andExpect(jsonPath("$.data.user.userId").value(userId))
                .andExpect(jsonPath("$.message").value("Slovník úspěšně nahrán: " + providedName));
    }

    @Test
    void testDeleteOntology_Success() throws Exception {
        Long ontologyId = 1L;

        doNothing().when(ontologyService).deleteOntology(ontologyId);

        mockMvc.perform(delete("/api/ontology/{ontologyId}/delete", ontologyId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Slovník úspěšně smazán."));
    }

    @Test
    void testDeleteOntology_NotFound() throws Exception {
        Long ontologyId = 999L;

        doThrow(new org.apache.jena.ontology.OntologyException("Ontologie s ID 999 nebyl nalezen"))
                .when(ontologyService).deleteOntology(ontologyId);

        mockMvc.perform(delete("/api/ontology/{ontologyId}/delete", ontologyId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Ontologie s ID 999 nebyl nalezen"));
    }

    @Test
    void testDeleteOntology_OntologyException() throws Exception {
        Long ontologyId = 1L;

        doThrow(new org.apache.jena.ontology.OntologyException("Chyba při mazání ontologie"))
                .when(ontologyService).deleteOntology(ontologyId);

        mockMvc.perform(delete("/api/ontology/{ontologyId}/delete", ontologyId))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Chyba při mazání ontologie"));
    }

    @Test
    void testDeleteOntology_UnexpectedException() throws Exception {
        Long ontologyId = 1L;

        doThrow(new RuntimeException("Neočekávaná chyba"))
                .when(ontologyService).deleteOntology(ontologyId);

        mockMvc.perform(delete("/api/ontology/{ontologyId}/delete", ontologyId))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Nastala neočekávaná chyba při mazání slovníku."));
    }
}