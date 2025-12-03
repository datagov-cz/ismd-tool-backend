package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.security.TestOntologySecurityService;
import com.dia.ismdtoolbackend.config.security.TestSecurityConfig;
import com.dia.ismdtoolbackend.config.security.WithMockSecurityUser;
import com.dia.ismdtoolbackend.controller.dto.GetOntologyDto;
import com.dia.ismdtoolbackend.exception.EmptyFileException;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.exception.UnsupportedRdfFormatException;
import com.dia.ismdtoolbackend.models.*;
import com.dia.ismdtoolbackend.service.OntologyDownloadService;
import com.dia.ismdtoolbackend.service.OntologyService;
import com.dia.ismdtoolbackend.service.OntologyUploadService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jena.riot.Lang;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for OntologyController with security context.
 * <p>
 * Uses @WebMvcTest to load only the web layer with Spring Security enabled.
 * Authentication is provided via @WithMockSecurityUser annotation.
 * Authorization checks (@PreAuthorize) are handled via TestOntologySecurityService.
 * <p>
 * Note: @MockBean is deprecated in Spring Boot 3.4+ but remains the recommended
 * approach for @WebMvcTest until a clear migration path is provided.
 */
@WebMvcTest(controllers = OntologyController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class
    })
@Import({TestSecurityConfig.class, TestOntologySecurityService.class, com.dia.ismdtoolbackend.config.GlobalExceptionHandler.class})
@ActiveProfiles("test")
class OntologyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OntologyUploadService ontologyUploadService;

    @MockBean
    private OntologyService ontologyService;

    @MockBean
    private OntologyDownloadService ontologyDownloadService;

    @MockBean
    private com.dia.ismdtoolbackend.service.ValidationService validationService;

    @MockBean
    private com.dia.ismdtoolbackend.client.ValidationClient validationClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // Reset security service to allow modifications by default
        TestOntologySecurityService.reset();
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
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
        when(ontologyUploadService.uploadFromFile(any(), eq(providedName), eq(userId)))
                .thenReturn(expectedMetadata);

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
    @WithMockSecurityUser(userId = "user123")
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
        when(ontologyUploadService.uploadFromFile(any(), isNull(), eq(userId)))
                .thenReturn(expectedMetadata);

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(file))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.graphName").value("generated-graph-name"))
                .andExpect(jsonPath("$.data.user.userId").value(userId))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    @WithMockSecurityUser(userId= "user123")
    void testUploadFromFile_EmptyFile() throws Exception {
        String userId = "user123";
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file",
                "empty.ttl",
                "text/turtle",
                new byte[0]
        );

        when(ontologyUploadService.uploadFromFile(any(), any(), eq(userId)))
                .thenThrow(new EmptyFileException("Soubor je prázdný."));

        TestOntologySecurityService.setAllowModify(true);
        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(emptyFile))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Soubor je prázdný."));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testUploadFromFile_UnsupportedRDFFormat() throws Exception {
        String userId = "user123";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.unknown",
                "application/unknown",
                "some content".getBytes()
        );

        when(ontologyUploadService.uploadFromFile(any(), any(), eq(userId)))
                .thenThrow(new UnsupportedRdfFormatException("RDF jazyk není podporován."));

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
        when(ontologyUploadService.uploadFromFile(any(), any(), eq(userId)))
                .thenThrow(new RuntimeException("Parse error"));

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(file))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testUploadFromFile_MissingFile() throws Exception {
        String userId = "user123";
        String providedName = "jsonld-ontology";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.jsonld",
                "application/ld+json",
                "".getBytes()
        );

        when(ontologyUploadService.uploadFromFile(any(), eq(providedName), eq(userId)))
                .thenThrow(new EmptyFileException("Soubor je prázdný."));

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(file)
                        .param("providedName", providedName))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
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
        when(ontologyUploadService.uploadFromFile(any(), eq(providedName), eq(userId)))
                .thenReturn(expectedMetadata);

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(file)
                        .param("providedName", providedName))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.graphName").value(providedName))
                .andExpect(jsonPath("$.data.user.userId").value(userId));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testUploadFromFile_LargeFile() throws Exception {
        String userId = "user123";
        MockMultipartFile largeFile = getMockMultipartFile();

        OntologyMetadataModel expectedMetadata = new OntologyMetadataModel();
        expectedMetadata.setGraphName("large-ontology");
        expectedMetadata.setUser(new UserModel(userId));

        when(ontologyUploadService.determineRDFFormat(any())).thenReturn(Lang.TURTLE);
        when(ontologyUploadService.uploadFromFile(any(), isNull(), eq(userId)))
                .thenReturn(expectedMetadata);

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(largeFile))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.graphName").value("large-ontology"))
                .andExpect(jsonPath("$.data.user.userId").value(userId));
    }

    private static MockMultipartFile getMockMultipartFile() {
        StringBuilder largeContent = new StringBuilder();
        largeContent.append("@prefix owl: <http://www.w3.org/2002/07/owl#> .");
        largeContent.append("<http://example.org/test> a owl:Ontology .");

        for (int i = 0; i < 1000; i++) {
            largeContent.append(String.format("<http://example.org/entity%d> a owl:Class .", i));
        }

        return new MockMultipartFile(
                "file",
                "large.ttl",
                "text/turtle",
                largeContent.toString().getBytes()
        );
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
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
        when(ontologyUploadService.uploadFromFile(any(), eq(providedName), eq(userId)))
                .thenReturn(existingMetadata);

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
    @WithMockSecurityUser(userId = "user123")
    void testDeleteOntology_Success() throws Exception {
        Long ontologyId = 1L;

        TestOntologySecurityService.setAllowModify(true);
        doNothing().when(ontologyService).deleteOntology(ontologyId);

        mockMvc.perform(delete("/api/ontology/{ontologyId}/delete", ontologyId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Slovník úspěšně smazán."));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testDeleteOntology_NotFound() throws Exception {
        Long ontologyId = 999L;

        TestOntologySecurityService.setAllowModify(true);
        doThrow(new com.dia.ismdtoolbackend.exception.OntologyNotFoundException("Slovník s ID 999 nebyl nalezen"))
                .when(ontologyService).deleteOntology(ontologyId);

        mockMvc.perform(delete("/api/ontology/{ontologyId}/delete", ontologyId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Slovník s ID 999 nebyl nalezen"));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testDeleteOntology_AccessDenied() throws Exception {
        Long ontologyId = 1L;

        TestOntologySecurityService.setAllowModify(false);

        mockMvc.perform(delete("/api/ontology/{ontologyId}/delete", ontologyId))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Přístup odepřen: nemáte oprávnění k této operaci."));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testDeleteOntology_UnexpectedException() throws Exception {
        Long ontologyId = 1L;

        TestOntologySecurityService.setAllowModify(true);
        doThrow(new RuntimeException("Neočekávaná chyba"))
                .when(ontologyService).deleteOntology(ontologyId);

        mockMvc.perform(delete("/api/ontology/{ontologyId}/delete", ontologyId))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Nastala neočekávaná chyba."));
    }

    // ========== Create Ontology Tests ==========

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testCreateOntology_Success() throws Exception {
        String userId = "user123";
        OntologyCreateModel createModel = getOntologyCreateModel();

        OntologyMetadataModel expectedMetadata = new OntologyMetadataModel();
        expectedMetadata.setGraphName("http://example.org/test-ontology");

        when(ontologyService.createOntology(any(OntologyCreateModel.class), eq(userId)))
                .thenReturn(expectedMetadata);

        mockMvc.perform(post("/api/ontology/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createModel)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.graphName").value("http://example.org/test-ontology"))
                .andExpect(jsonPath("$.message").isString());
    }

    private static OntologyCreateModel getOntologyCreateModel() {
        OntologyCreateModel createModel = new OntologyCreateModel();
        createModel.setNamespace("http://example.org/");
        NameModel nameModel = new NameModel();
        nameModel.setName(java.util.Map.of("cs", "test-ontology"));
        createModel.setNameModel(nameModel);
        DescriptionModel descModel = new DescriptionModel();
        descModel.setDescription(java.util.Map.of("cs", "Test description"));
        createModel.setDescriptionModel(descModel);
        return createModel;
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testCreateOntology_ValidationError() throws Exception {
        String userId = "user123";
        OntologyCreateModel createModel = new OntologyCreateModel();
        NameModel nameModel = new NameModel();
        nameModel.setName(java.util.Map.of("cs", "test-ontology"));
        createModel.setNameModel(nameModel);
        DescriptionModel descModel = new DescriptionModel();
        descModel.setDescription(java.util.Map.of("cs", "Test description"));
        createModel.setDescriptionModel(descModel);

        when(ontologyService.createOntology(any(), eq(userId)))
                .thenThrow(new com.dia.ismdtoolbackend.exception.OntologyValidationException("Namespace je povinný"));

        mockMvc.perform(post("/api/ontology/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createModel)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Namespace je povinný"));
    }

    // ========== Edit Ontology Tests ==========

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testEditOntology_Success() throws Exception {
        Long ontologyId = 1L;
        OntologyEditModel editModel = new OntologyEditModel();
        editModel.setOntologyIRI("http://example.org/test-ontology");

        OntologyMetadataModel expectedMetadata = new OntologyMetadataModel();
        expectedMetadata.setGraphName("http://example.org/test-ontology");

        TestOntologySecurityService.setAllowModify(true);
        when(ontologyService.editOntology(any(OntologyEditModel.class)))
                .thenReturn(expectedMetadata);

        mockMvc.perform(patch("/api/ontology/{ontologyId}/edit", ontologyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(editModel)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.graphName").value("http://example.org/test-ontology"))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testEditOntology_NotFound() throws Exception {
        Long ontologyId = 999L;
        OntologyEditModel editModel = new OntologyEditModel();
        editModel.setOntologyIRI("http://example.org/nonexistent");

        TestOntologySecurityService.setAllowModify(true);
        when(ontologyService.editOntology(any()))
                .thenThrow(new com.dia.ismdtoolbackend.exception.OntologyNotFoundException("Slovník nebyl nalezen"));

        mockMvc.perform(patch("/api/ontology/{ontologyId}/edit", ontologyId)
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
                .thenThrow(new OntologyNotFoundException("Ontology not found"));

        mockMvc.perform(get("/api/ontology/{ontologyId}/download", ontologyId)
                        .param("format", "ttl"))
                .andExpect(status().isNotFound());
    }

    // ========== Get Ontology Detail Tests ==========

    @Test
    void testGetOntologyDetail_Success() throws Exception {
        String slug = "test-ontology";
        GetOntologyDto ontologyDto = new GetOntologyDto();
        OntologyMetadataModel metadataModel = new OntologyMetadataModel();
        metadataModel.setSlug(slug);
        metadataModel.setGraphName("http://example.org/test-ontology");

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

        ontologyDto.setOntologyMetadata(metadataModel);
        ontologyDto.setOntologyDetail(detailModel);

        when(ontologyService.getOntologyDetailModel(slug))
                .thenReturn(ontologyDto);

        mockMvc.perform(get("/api/ontology/{slug}/detail", slug))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    void testGetOntologyDetail_NotFound() throws Exception {
        String slug = "non-existent-ontology";

        when(ontologyService.getOntologyDetailModel(slug))
                .thenThrow(new OntologyNotFoundException("Ontology not found"));

        mockMvc.perform(get("/api/ontology/{slug}/detail", slug))
                .andExpect(status().isNotFound());
    }

    // ========== Get Ontology List Tests ==========

    @Test
    void testGetOntologyList_AllOntologies() throws Exception {
        OntologyMetadataModel ontology1 = new OntologyMetadataModel();
        ontology1.setId(1L);
        ontology1.setGraphName("http://example.org/ontology1");
        ontology1.setSlug("ontology-1");

        OntologyMetadataModel ontology2 = new OntologyMetadataModel();
        ontology2.setId(2L);
        ontology2.setGraphName("http://example.org/ontology2");
        ontology2.setSlug("ontology-2");

        when(ontologyService.getAll(null, null))
                .thenReturn(java.util.List.of(ontology1, ontology2));

        mockMvc.perform(get("/api/ontology/list"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].graphName").value("http://example.org/ontology1"))
                .andExpect(jsonPath("$.data[1].id").value(2))
                .andExpect(jsonPath("$.data[1].graphName").value("http://example.org/ontology2"))
                .andExpect(jsonPath("$.message").value("Žádost o seznam slovníků proběhla úspěšně."));
    }

    @Test
    void testGetOntologyList_ByUserId() throws Exception {
        String userId = "user123";
        OntologyMetadataModel ontology1 = new OntologyMetadataModel();
        ontology1.setId(1L);
        ontology1.setGraphName("http://example.org/ontology1");
        ontology1.setUser(new UserModel(userId));

        when(ontologyService.getAll(userId, null))
                .thenReturn(java.util.List.of(ontology1));

        mockMvc.perform(get("/api/ontology/list")
                        .param("userId", userId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].user.userId").value(userId))
                .andExpect(jsonPath("$.message").value("Žádost o seznam slovníků proběhla úspěšně."));
    }

    @Test
    void testGetOntologyList_ByPublishedStatus() throws Exception {
        OntologyMetadataModel ontology1 = new OntologyMetadataModel();
        ontology1.setId(1L);
        ontology1.setGraphName("http://example.org/ontology1");
        ontology1.setIsPublished(true);

        when(ontologyService.getAll(null, true))
                .thenReturn(java.util.List.of(ontology1));

        mockMvc.perform(get("/api/ontology/list")
                        .param("isPublished", "true"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].isPublished").value(true))
                .andExpect(jsonPath("$.message").value("Žádost o seznam slovníků proběhla úspěšně."));
    }

    @Test
    void testGetOntologyList_BySlugs() throws Exception {
        OntologyMetadataModel ontology1 = new OntologyMetadataModel();
        ontology1.setId(1L);
        ontology1.setSlug("ontology-1");
        ontology1.setGraphName("http://example.org/ontology1");

        OntologyMetadataModel ontology2 = new OntologyMetadataModel();
        ontology2.setId(2L);
        ontology2.setSlug("ontology-2");
        ontology2.setGraphName("http://example.org/ontology2");

        when(ontologyService.getBySlugs(java.util.List.of("ontology-1", "ontology-2")))
                .thenReturn(java.util.List.of(ontology1, ontology2));

        mockMvc.perform(get("/api/ontology/list")
                        .param("slugs", "ontology-1", "ontology-2"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].slug").value("ontology-1"))
                .andExpect(jsonPath("$.data[1].slug").value("ontology-2"))
                .andExpect(jsonPath("$.message").value("Žádost o seznam slovníků proběhla úspěšně."));
    }

    @Test
    void testGetOntologyList_EmptyResult() throws Exception {
        when(ontologyService.getAll(null, null))
                .thenReturn(java.util.List.of());

        mockMvc.perform(get("/api/ontology/list"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.message").value("Žádost o seznam slovníků proběhla úspěšně."));
    }

    @Test
    void testGetOntologyList_CombinedFilters() throws Exception {
        String userId = "user123";
        OntologyMetadataModel ontology1 = new OntologyMetadataModel();
        ontology1.setId(1L);
        ontology1.setGraphName("http://example.org/ontology1");
        ontology1.setUser(new UserModel(userId));
        ontology1.setIsPublished(true);

        when(ontologyService.getAll(userId, true))
                .thenReturn(java.util.List.of(ontology1));

        mockMvc.perform(get("/api/ontology/list")
                        .param("userId", userId)
                        .param("isPublished", "true"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].user.userId").value(userId))
                .andExpect(jsonPath("$.data[0].isPublished").value(true))
                .andExpect(jsonPath("$.message").value("Žádost o seznam slovníků proběhla úspěšně."));
    }

    // ========== Validate Ontology Tests ==========

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testValidateOntology_Success() throws Exception {
        String slug = "test-ontology";
        String userId = "user123";

        OntologyMetadataModel ontologyMetadata = new OntologyMetadataModel();
        ontologyMetadata.setId(1L);
        ontologyMetadata.setGraphName("http://example.org/test-ontology");
        ontologyMetadata.setSlug(slug);

        com.dia.validation.ValidationReportDto validationReport = new com.dia.validation.ValidationReportDto();

        TestOntologySecurityService.setAllowModify(true);
        when(ontologyService.getTtlContentFromOntology(any()))
                .thenReturn("@prefix owl: <http://www.w3.org/2002/07/owl#> .");
        when(validationClient.requestValidation(anyString(), anyString()))
                .thenReturn(java.util.Optional.of(validationReport));
        doNothing().when(validationService).saveValidationReport(any(), any(), eq(userId));

        mockMvc.perform(post("/api/ontology/{slug}/validate", slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ontologyMetadata)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.message").value("Validace proběhla úspěšně."));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testValidateOntology_ValidationFailed() throws Exception {
        String slug = "test-ontology";

        OntologyMetadataModel ontologyMetadata = new OntologyMetadataModel();
        ontologyMetadata.setId(1L);
        ontologyMetadata.setGraphName("http://example.org/test-ontology");
        ontologyMetadata.setSlug(slug);

        com.dia.validation.ValidationReportDto validationReport = new com.dia.validation.ValidationReportDto();

        TestOntologySecurityService.setAllowModify(true);
        when(ontologyService.getTtlContentFromOntology(any()))
                .thenReturn("@prefix owl: <http://www.w3.org/2002/07/owl#> .");
        when(validationClient.requestValidation(anyString(), anyString()))
                .thenReturn(java.util.Optional.of(validationReport));

        mockMvc.perform(post("/api/ontology/{slug}/validate", slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ontologyMetadata)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.message").value("Validace proběhla úspěšně."));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testValidateOntology_ServiceUnavailable() throws Exception {
        String slug = "test-ontology";

        OntologyMetadataModel ontologyMetadata = new OntologyMetadataModel();
        ontologyMetadata.setId(1L);
        ontologyMetadata.setGraphName("http://example.org/test-ontology");
        ontologyMetadata.setSlug(slug);

        TestOntologySecurityService.setAllowModify(true);
        when(ontologyService.getTtlContentFromOntology(any()))
                .thenReturn("@prefix owl: <http://www.w3.org/2002/07/owl#> .");
        when(validationClient.requestValidation(anyString(), anyString()))
                .thenReturn(java.util.Optional.empty());

        mockMvc.perform(post("/api/ontology/{slug}/validate", slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ontologyMetadata)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Validace se nezdařila - validační služba nevrátila odpověď."));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testValidateOntology_AccessDenied() throws Exception {
        String slug = "test-ontology";

        OntologyMetadataModel ontologyMetadata = new OntologyMetadataModel();
        ontologyMetadata.setId(1L);
        ontologyMetadata.setGraphName("http://example.org/test-ontology");

        TestOntologySecurityService.setAllowModify(false);

        mockMvc.perform(post("/api/ontology/{slug}/validate", slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ontologyMetadata)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Přístup odepřen: nemáte oprávnění k této operaci."));
    }

    // ========== Request Catalog Record Tests ==========

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testRequestCatalogRecord_Success() throws Exception {
        String slug = "test-ontology";

        OntologyMetadataModel ontologyMetadata = new OntologyMetadataModel();
        ontologyMetadata.setId(1L);
        ontologyMetadata.setGraphName("http://example.org/test-ontology");
        ontologyMetadata.setSlug(slug);

        com.dia.validation.ValidationReportDto validationReport = new com.dia.validation.ValidationReportDto();

        com.dia.ismdtoolbackend.controller.dto.CatalogRequestDto catalogRequest =
                new com.dia.ismdtoolbackend.controller.dto.CatalogRequestDto();
        catalogRequest.setOntologyMetadata(ontologyMetadata);
        catalogRequest.setValidationReport(validationReport);

        com.dia.dto.CatalogRecordDto catalogRecordDto = mock(com.dia.dto.CatalogRecordDto.class);

        TestOntologySecurityService.setAllowModify(true);
        when(ontologyService.getTtlContentFromOntology(any()))
                .thenReturn("@prefix owl: <http://www.w3.org/2002/07/owl#> .");
        when(validationClient.requestCatalogRecord(any()))
                .thenReturn(java.util.Optional.of(catalogRecordDto));

        mockMvc.perform(post("/api/ontology/{slug}/catalog-record", slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(catalogRequest)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.message").value("Žádost o katalogizační záznam proběhla úspěšně."));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testRequestCatalogRecord_ServiceUnavailable() throws Exception {
        String slug = "test-ontology";

        OntologyMetadataModel ontologyMetadata = new OntologyMetadataModel();
        ontologyMetadata.setId(1L);
        ontologyMetadata.setGraphName("http://example.org/test-ontology");

        com.dia.validation.ValidationReportDto validationReport = new com.dia.validation.ValidationReportDto();

        com.dia.ismdtoolbackend.controller.dto.CatalogRequestDto catalogRequest =
                new com.dia.ismdtoolbackend.controller.dto.CatalogRequestDto();
        catalogRequest.setOntologyMetadata(ontologyMetadata);
        catalogRequest.setValidationReport(validationReport);

        TestOntologySecurityService.setAllowModify(true);
        when(ontologyService.getTtlContentFromOntology(any()))
                .thenReturn("@prefix owl: <http://www.w3.org/2002/07/owl#> .");
        when(validationClient.requestCatalogRecord(any()))
                .thenReturn(java.util.Optional.empty());

        mockMvc.perform(post("/api/ontology/{slug}/catalog-record", slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(catalogRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Žádost o katalogizační záznam se nezdařila - validační služba nevrátila odpověď, nebo je nedostupná."));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testRequestCatalogRecord_AccessDenied() throws Exception {
        String slug = "test-ontology";

        OntologyMetadataModel ontologyMetadata = new OntologyMetadataModel();
        ontologyMetadata.setId(1L);
        ontologyMetadata.setGraphName("http://example.org/test-ontology");

        com.dia.validation.ValidationReportDto validationReport = new com.dia.validation.ValidationReportDto();

        com.dia.ismdtoolbackend.controller.dto.CatalogRequestDto catalogRequest =
                new com.dia.ismdtoolbackend.controller.dto.CatalogRequestDto();
        catalogRequest.setOntologyMetadata(ontologyMetadata);
        catalogRequest.setValidationReport(validationReport);

        TestOntologySecurityService.setAllowModify(false);

        mockMvc.perform(post("/api/ontology/{slug}/catalog-record", slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(catalogRequest)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Přístup odepřen: nemáte oprávnění k této operaci."));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testRequestCatalogRecord_InvalidValidationReport() throws Exception {
        String slug = "test-ontology";

        OntologyMetadataModel ontologyMetadata = new OntologyMetadataModel();
        ontologyMetadata.setId(1L);
        ontologyMetadata.setGraphName("http://example.org/test-ontology");

        com.dia.validation.ValidationReportDto validationReport = new com.dia.validation.ValidationReportDto();

        com.dia.ismdtoolbackend.controller.dto.CatalogRequestDto catalogRequest =
                new com.dia.ismdtoolbackend.controller.dto.CatalogRequestDto();
        catalogRequest.setOntologyMetadata(ontologyMetadata);
        catalogRequest.setValidationReport(validationReport);

        TestOntologySecurityService.setAllowModify(true);
        when(ontologyService.getTtlContentFromOntology(any()))
                .thenReturn("@prefix owl: <http://www.w3.org/2002/07/owl#> .");
        when(validationClient.requestCatalogRecord(any()))
                .thenReturn(java.util.Optional.empty());

        mockMvc.perform(post("/api/ontology/{slug}/catalog-record", slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(catalogRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}