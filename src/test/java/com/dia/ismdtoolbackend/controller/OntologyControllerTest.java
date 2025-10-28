package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.models.*;
import com.dia.ismdtoolbackend.service.OntologyDownloadService;
import com.dia.ismdtoolbackend.service.OntologyService;
import com.dia.ismdtoolbackend.service.OntologyUploadService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jena.riot.Lang;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@Disabled("TODO implement test security config")
class OntologyControllerTest {

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockMvc mockMvc;

    @Mock(lenient = true)
    private OntologyUploadService ontologyUploadService;

    @Mock(lenient = true)
    private OntologyService ontologyService;

    @Mock
    private OntologyDownloadService ontologyDownloadService;

    @InjectMocks
    private OntologyController ontologyController;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(ontologyController).build();
        objectMapper = new ObjectMapper();
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
        // TODO modify to reflect current implementation
        //when(ontologyUploadService.uploadFromFile(any(), eq(providedName), eq(Lang.TURTLE), eq(userId)))
                //.thenReturn(expectedMetadata);

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(file)
                        .param("providedName", providedName))
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
        // TODO modify to reflect current implementation
        //when(ontologyUploadService.uploadFromFile(any(), isNull(), eq(Lang.TURTLE), eq(userId)))
                //.thenReturn(expectedMetadata);

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(file))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.graphName").value("generated-graph-name"))
                .andExpect(jsonPath("$.data.user.userId").value(userId))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void testUploadFromFile_EmptyFile() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file",
                "empty.ttl",
                "text/turtle",
                new byte[0]
        );

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(emptyFile))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Soubor je prázdný."));
    }

    @Test
    void testUploadFromFile_UnsupportedRDFFormat() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.unknown",
                "application/unknown",
                "some content".getBytes()
        );

        when(ontologyUploadService.determineRDFFormat(any())).thenReturn(null);

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(file))
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
        // TODO modify to reflect current implementation
        //when(ontologyUploadService.uploadFromFile(any(), any(), eq(Lang.TURTLE), eq(userId)))
                //.thenThrow(new RuntimeException("Parse error"));

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(file))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void testUploadFromFile_MissingFile() throws Exception {
        mockMvc.perform(multipart("/api/ontology/upload"))
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
        // TODO modify to reflect current implementation
        //when(ontologyUploadService.uploadFromFile(any(), eq(providedName), eq(Lang.JSONLD), eq(userId)))
                //.thenReturn(expectedMetadata);

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(file)
                        .param("providedName", providedName))
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
        // TODO modify to reflect current implementation
        //when(ontologyUploadService.uploadFromFile(any(), isNull(), eq(Lang.TURTLE), eq(userId)))
                //.thenReturn(expectedMetadata);

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(largeFile))
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
        // TODO modify to reflect current implementation
        //when(ontologyUploadService.uploadFromFile(any(), eq(providedName), eq(Lang.TURTLE), eq(userId)))
                //.thenReturn(existingMetadata);

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(file)
                        .param("providedName", providedName))
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

        doThrow(new org.apache.jena.ontology.OntologyException("Slovník s ID 999 nebyl nalezen"))
                .when(ontologyService).deleteOntology(ontologyId);

        mockMvc.perform(delete("/api/ontology/{ontologyId}/delete", ontologyId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Slovník s ID 999 nebyl nalezen"));
    }

    @Test
    void testDeleteOntology_AccessDenied() throws Exception {
        Long ontologyId = 1L;

        doThrow(new org.apache.jena.ontology.OntologyException("Chyba při mazání slovníku"))
                .when(ontologyService).deleteOntology(ontologyId);

        mockMvc.perform(delete("/api/ontology/{ontologyId}/delete", ontologyId))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Chyba při mazání slovníku"));
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

    // ========== Create Ontology Tests ==========

    @Test
    void testCreateOntology_Success() throws Exception {
        String userId = "user123";
        OntologyCreateModel createModel = new OntologyCreateModel();
        createModel.setNamespace("http://example.org/");
        NameModel nameModel = new NameModel();
        nameModel.setName("test-ontology");
        nameModel.setLanguageTag("cs");
        createModel.setNameModel(nameModel);
        DescriptionModel descModel = new DescriptionModel();
        descModel.setDescription("Test description");
        descModel.setLanguageTag("cs");
        createModel.setDescriptionModel(descModel);

        OntologyMetadataModel expectedMetadata = new OntologyMetadataModel();
        expectedMetadata.setGraphName("http://example.org/test-ontology");

        when(ontologyService.createOntology(any(OntologyCreateModel.class), eq(userId)))
                .thenReturn(expectedMetadata);

        mockMvc.perform(post("/api/ontology/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createModel))
                        .param("userId", userId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.graphName").value("http://example.org/test-ontology"))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void testCreateOntology_EmptyUserId() throws Exception {
        OntologyCreateModel createModel = new OntologyCreateModel();
        createModel.setNamespace("http://example.org/");
        NameModel nameModel = new NameModel();
        nameModel.setName("test-ontology");
        nameModel.setLanguageTag("cs");
        createModel.setNameModel(nameModel);
        DescriptionModel descModel = new DescriptionModel();
        descModel.setDescription("Test description");
        descModel.setLanguageTag("cs");
        createModel.setDescriptionModel(descModel);

        mockMvc.perform(post("/api/ontology/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createModel))
                        .param("userId", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("ID uživatele je povinné."));
    }

    @Test
    void testCreateOntology_ValidationError() throws Exception {
        String userId = "user123";
        OntologyCreateModel createModel = new OntologyCreateModel();
        NameModel nameModel = new NameModel();
        nameModel.setName("test-ontology");
        nameModel.setLanguageTag("cs");
        createModel.setNameModel(nameModel);
        DescriptionModel descModel = new DescriptionModel();
        descModel.setDescription("Test description");
        descModel.setLanguageTag("cs");
        createModel.setDescriptionModel(descModel);

        when(ontologyService.createOntology(any(), eq(userId)))
                .thenThrow(new org.apache.jena.ontology.OntologyException("Namespace je povinný"));

        mockMvc.perform(post("/api/ontology/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createModel))
                        .param("userId", userId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Namespace je povinný"));
    }

    // ========== Edit Ontology Tests ==========

    @Test
    void testEditOntology_Success() throws Exception {
        OntologyEditModel editModel = new OntologyEditModel();
        editModel.setOntologyIRI("http://example.org/test-ontology");

        OntologyMetadataModel expectedMetadata = new OntologyMetadataModel();
        expectedMetadata.setGraphName("http://example.org/test-ontology");

        when(ontologyService.editOntology(any(OntologyEditModel.class)))
                .thenReturn(expectedMetadata);

        mockMvc.perform(patch("/api/ontology/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(editModel)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.graphName").value("http://example.org/test-ontology"))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void testEditOntology_NotFound() throws Exception {
        OntologyEditModel editModel = new OntologyEditModel();
        editModel.setOntologyIRI("http://example.org/nonexistent");

        when(ontologyService.editOntology(any()))
                .thenThrow(new org.apache.jena.ontology.OntologyException("Slovník nebyl nalezen"));

        mockMvc.perform(patch("/api/ontology/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(editModel)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Slovník nebyl nalezen"));
    }

    // ========== Download Ontology Tests ==========

    @Test
    void testDownloadOntology_TurtleFormat() throws Exception {
        Long ontologyId = 1L;
        String ttlContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> .";

        when(ontologyDownloadService.downloadOntology(ontologyId, "ttl"))
                .thenReturn(ttlContent);

        mockMvc.perform(get("/api/ontology/{ontologyId}/download", ontologyId)
                        .param("format", "ttl"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"ontology_1.ttl\""))
                .andExpect(content().contentType("text/turtle"))
                .andExpect(content().string(ttlContent));
    }

    @Test
    void testDownloadOntology_JsonLdFormat() throws Exception {
        Long ontologyId = 1L;
        String jsonLdContent = "{\"@context\":{},\"@graph\":[]}";

        when(ontologyDownloadService.downloadOntology(ontologyId, "json-ld"))
                .thenReturn(jsonLdContent);

        mockMvc.perform(get("/api/ontology/{ontologyId}/download", ontologyId)
                        .param("format", "json-ld"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"ontology_1.jsonld\""))
                .andExpect(content().contentType("application/ld+json"))
                .andExpect(content().string(jsonLdContent));
    }

    @Test
    void testDownloadOntology_InvalidFormat() throws Exception {
        Long ontologyId = 1L;

        when(ontologyDownloadService.downloadOntology(ontologyId, "invalid"))
                .thenThrow(new IllegalArgumentException("Unsupported format"));

        mockMvc.perform(get("/api/ontology/{ontologyId}/download", ontologyId)
                        .param("format", "invalid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testDownloadOntology_NotFound() throws Exception {
        Long ontologyId = 999L;

        when(ontologyDownloadService.downloadOntology(ontologyId, "ttl"))
                .thenThrow(new RuntimeException("Ontology not found"));

        mockMvc.perform(get("/api/ontology/{ontologyId}/download", ontologyId)
                        .param("format", "ttl"))
                .andExpect(status().isNotFound());
    }

    // ========== Get Ontology Detail Tests ==========

    @Test
    void testGetOntologyDetail_Success() throws Exception {
        Long ontologyId = 1L;
        OntologyDetailModel detailModel = OntologyDetailModel.builder()
                .context("http://example.org/context")
                .iri("http://example.org/test-ontology")
                .types(java.util.List.of())
                .name(java.util.Map.of())
                .description(java.util.Map.of())
                .creationDate("")
                .modificationDate("")
                .concepts(java.util.List.of())
                .build();

        when(ontologyService.getOntologyDetailModel(ontologyId))
                .thenReturn(detailModel);

        mockMvc.perform(get("/api/ontology/{ontologyId}/detail", ontologyId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    void testGetOntologyDetail_NotFound() throws Exception {
        Long ontologyId = 999L;

        when(ontologyService.getOntologyDetailModel(ontologyId))
                .thenThrow(new RuntimeException("Ontology not found"));

        mockMvc.perform(get("/api/ontology/{ontologyId}/detail", ontologyId))
                .andExpect(status().isNotFound());
    }
}