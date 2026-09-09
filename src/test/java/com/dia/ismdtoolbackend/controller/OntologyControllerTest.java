package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.client.ValidationClient;
import com.dia.ismdtoolbackend.config.ValidationConfig;
import com.dia.ismdtoolbackend.config.security.TestOntologySecurityService;
import com.dia.ismdtoolbackend.config.security.TestSecurityConfig;
import com.dia.ismdtoolbackend.config.security.WithMockSecurityUser;
import com.dia.ismdtoolbackend.controller.dto.GetOntologyDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyCreateWithConceptsResponseDto;
import com.dia.ismdtoolbackend.exception.OntologyCreationConflictException;
import static com.dia.ismdtoolbackend.support.VocabularyCreationRequests.sample;
import com.dia.ismdtoolbackend.controller.dto.OntologyIriCheckResponseDto;
import com.dia.ismdtoolbackend.controller.dto.MinimalConceptDto;
import com.dia.ismdtoolbackend.controller.dto.MissingConceptDto;
import com.dia.ismdtoolbackend.enums.NormalizeMode;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.exception.EmptyFileException;
import com.dia.ismdtoolbackend.exception.InSchemeDecisionRequiredException;
import com.dia.ismdtoolbackend.exception.NkdResourceNotFoundException;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.exception.UnsupportedRdfFormatException;
import com.dia.ismdtoolbackend.models.*;
import com.dia.ismdtoolbackend.service.NkdDetailService;
import com.dia.ismdtoolbackend.service.OntologyDownloadService;
import com.dia.ismdtoolbackend.service.OntologyService;
import com.dia.ismdtoolbackend.service.OntologyUploadService;
import com.dia.ismdtoolbackend.service.ValidationService;
import com.dia.validation.ValidationReportDto;
import com.dia.validation.ValidationResult;
import com.dia.validation.ValidationSeverity;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.jena.riot.Lang;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
 * Note: @MockitoBean replaces Spring Boot's deprecated @MockBean in these MVC
 * slice tests.
 */
@WebMvcTest(controllers = OntologyController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class,
        org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration.class,
        org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration.class,
        org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration.class
    })
@Import({TestSecurityConfig.class, TestOntologySecurityService.class, com.dia.ismdtoolbackend.config.GlobalExceptionHandler.class})
@ActiveProfiles("junit")
class OntologyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OntologyUploadService ontologyUploadService;

    @MockitoBean
    private OntologyService ontologyService;

    @MockitoBean
    private OntologyDownloadService ontologyDownloadService;

    @MockitoBean
    private ValidationService validationService;

    @MockitoBean
    private ValidationClient validationClient;

    @MockitoBean
    private ValidationConfig validationConfig;

    @MockitoBean
    private NkdDetailService nkdDetailService;

    @MockitoBean
    private com.dia.ismdtoolbackend.service.snapshot.NkdSnapshotWarmer nkdSnapshotWarmer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // Reset security service to allow modifications by default
        TestOntologySecurityService.reset();
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void createWithConcepts_ReturnsCreatedAndRefMapping() throws Exception {
        var response = new OntologyCreateWithConceptsResponseDto(new OntologyMetadataModel(), java.util.Map.of("driver", "https://example.org/driver"));
        when(ontologyService.createWithConcepts(any(), eq("user123"))).thenReturn(response);
        mockMvc.perform(post("/api/ontology/create-with-concepts").contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(sample())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.conceptIris.driver").value("https://example.org/driver"));
        verify(ontologyService).createWithConcepts(argThat(r -> r.classes().size() == 2 && r.attributes().size() == 1 && r.relationships().size() == 1), eq("user123"));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void createWithConcepts_ConflictReturns409() throws Exception {
        when(ontologyService.createWithConcepts(any(), anyString())).thenThrow(new OntologyCreationConflictException("IRI is taken"));
        mockMvc.perform(post("/api/ontology/create-with-concepts").contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(sample())))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void createWithConcepts_RejectsMissingCollectionsAndNullTerms() throws Exception {
        var json = new ObjectMapper().valueToTree(sample());
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("classes");
        mockMvc.perform(post("/api/ontology/create-with-concepts").contentType(MediaType.APPLICATION_JSON).content(json.toString()))
                .andExpect(status().isBadRequest());
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).putArray("classes").addNull();
        mockMvc.perform(post("/api/ontology/create-with-concepts").contentType(MediaType.APPLICATION_JSON).content(json.toString()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ontologyService);
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void checkIri_AcceptsNameAndNamespaceWithoutDescription() throws Exception {
        when(ontologyService.checkIri(any())).thenReturn(
                new OntologyIriCheckResponseDto("http://example.org/test", true, true));

        mockMvc.perform(post("/api/ontology/check-iri")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"namespace":"http://example.org/","nameModel":{"name":{"cs":"Test"}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.iri").value("http://example.org/test"))
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.available").value(true));
        verify(ontologyService).checkIri(argThat(request -> request.namespace().equals("http://example.org/")
                && request.nameModel().getName().get("cs").equals("Test")));
        verifyNoMoreInteractions(ontologyService);
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void checkIri_MissingNameModel_ReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/ontology/check-iri")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ontologyService);
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void checkIri_InvalidIri_ReturnsFlags() throws Exception {
        when(ontologyService.checkIri(any())).thenReturn(new OntologyIriCheckResponseDto("invalid", false, false));
        mockMvc.perform(post("/api/ontology/check-iri")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"namespace":"invalid","nameModel":{"name":{"cs":"Test"}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.available").value(false));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testUploadFromFile_Success() throws Exception {
        String userId = "user123";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.ttl",
                "text/turtle",
                "@prefix owl: <http://www.w3.org/2002/07/owl#> . <http://example.org/test> a owl:Ontology .".getBytes()
        );

        OntologyMetadataModel expectedMetadata = new OntologyMetadataModel();
        expectedMetadata.setGraphName("file");
        expectedMetadata.setUser(new UserModel(userId));

        when(ontologyUploadService.determineRDFFormat(any())).thenReturn(Lang.TURTLE);
        when(ontologyUploadService.uploadFromFile(any(), eq(userId), any(), any()))
                .thenReturn(expectedMetadata);

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(file))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.graphName").value("file"))
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
        when(ontologyUploadService.uploadFromFile(any(), eq(userId), any(), any()))
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

        when(ontologyUploadService.determineRDFFormat(any())).thenReturn(Lang.TURTLE);
        when(ontologyUploadService.uploadFromFile(any(), eq(userId), any(), any()))
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

        when(ontologyUploadService.determineRDFFormat(any())).thenReturn(Lang.TURTLE);
        when(ontologyUploadService.uploadFromFile(any(), eq(userId), any(), any()))
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
        when(ontologyUploadService.uploadFromFile(any(), eq(userId), any(), any()))
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
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.jsonld",
                "application/ld+json",
                "".getBytes()
        );

        when(ontologyUploadService.determineRDFFormat(any())).thenReturn(Lang.TURTLE);
        when(ontologyUploadService.uploadFromFile(any(), eq(userId), any(), any()))
                .thenThrow(new EmptyFileException("Soubor je prázdný."));

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testUploadFromFile_JsonLdFormat() throws Exception {
        String userId = "user123";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.jsonld",
                "application/ld+json",
                "{\"@context\":{\"owl\":\"http://www.w3.org/2002/07/owl#\"},\"@type\":\"owl:Ontology\"}".getBytes()
        );

        OntologyMetadataModel expectedMetadata = new OntologyMetadataModel();
        expectedMetadata.setGraphName("file");
        expectedMetadata.setUser(new UserModel(userId));

        when(ontologyUploadService.determineRDFFormat(any())).thenReturn(Lang.JSONLD);
        when(ontologyUploadService.uploadFromFile(any(), eq(userId), any(), any()))
                .thenReturn(expectedMetadata);

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(file))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.graphName").value("file"))
                .andExpect(jsonPath("$.data.user.userId").value(userId));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testUploadFromFile_missingInScheme_returns400WithDecisionPayload() throws Exception {
        String userId = "user123";
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.ttl", "text/turtle",
                "@prefix owl: <http://www.w3.org/2002/07/owl#> . <http://example.org/test> a owl:Ontology .".getBytes()
        );

        String graphName = "https://slovník.gov.cz/a3791";
        String conceptIri = graphName + "/pojem/vysoká-škola";
        when(ontologyUploadService.determineRDFFormat(any())).thenReturn(Lang.TURTLE);
        when(ontologyUploadService.uploadFromFile(any(), eq(userId), any(), any()))
                .thenThrow(new InSchemeDecisionRequiredException(
                        graphName,
                        List.of(new MissingConceptDto(conceptIri, "vysoká škola", graphName))));

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(file))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("MISSING_INSCHEME_DECISION_REQUIRED"))
                .andExpect(jsonPath("$.data.graphName").value(graphName))
                .andExpect(jsonPath("$.data.conceptsMissingInScheme[0].conceptIri").value(conceptIri))
                .andExpect(jsonPath("$.data.conceptsMissingInScheme[0].proposedInScheme").value(graphName));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testUploadFromFile_withDecisionParams_passesThroughToService() throws Exception {
        String userId = "user123";
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.ttl", "text/turtle",
                "@prefix owl: <http://www.w3.org/2002/07/owl#> . <http://example.org/test> a owl:Ontology .".getBytes()
        );

        OntologyMetadataModel expected = new OntologyMetadataModel();
        expected.setGraphName("file");
        expected.setUser(new UserModel(userId));

        when(ontologyUploadService.determineRDFFormat(any())).thenReturn(Lang.TURTLE);
        when(ontologyUploadService.uploadFromFile(any(), eq(userId), any(), any()))
                .thenReturn(expected);

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(file)
                        .param("normalizeMode", "PER_CONCEPT")
                        .param("conceptsToNormalize", "https://slovník.gov.cz/a3791/pojem/x")
                        .param("conceptsToNormalize", "https://slovník.gov.cz/a3791/pojem/y"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.graphName").value("file"));

        verify(ontologyUploadService).uploadFromFile(
                any(),
                eq(userId),
                eq(NormalizeMode.PER_CONCEPT),
                eq(List.of("https://slovník.gov.cz/a3791/pojem/x", "https://slovník.gov.cz/a3791/pojem/y")));
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
        when(ontologyUploadService.uploadFromFile(any(), eq(userId), any(), any()))
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
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.ttl",
                "text/turtle",
                "@prefix owl: <http://www.w3.org/2002/07/owl#> . <http://example.org/test> a owl:Ontology .".getBytes()
        );

        OntologyMetadataModel existingMetadata = new OntologyMetadataModel();
        existingMetadata.setId(1L);
        existingMetadata.setGraphName("file");
        existingMetadata.setUser(new UserModel(userId));

        when(ontologyUploadService.determineRDFFormat(any())).thenReturn(Lang.TURTLE);
        when(ontologyUploadService.uploadFromFile(any(), eq(userId), any(), any()))
                .thenReturn(existingMetadata);

        mockMvc.perform(multipart("/api/ontology/upload")
                        .file(file))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.graphName").value("file"))
                .andExpect(jsonPath("$.data.user.userId").value(userId))
                .andExpect(jsonPath("$.message").value("Slovník úspěšně nahrán: " + "file"));
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

        when(ontologyService.createOntology(any(OntologyCreateModel.class), eq(userId)))
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

        OntologyMetadataModel expectedMetadata = new OntologyMetadataModel();
        expectedMetadata.setGraphName("http://example.org/test-ontology");

        TestOntologySecurityService.setAllowModify(true);
        when(ontologyService.editOntology(eq(ontologyId), any(OntologyEditModel.class)))
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

        TestOntologySecurityService.setAllowModify(true);
        when(ontologyService.editOntology(eq(ontologyId), any()))
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

    @Test
    void testDownloadOntology_BlockedByValidationErrors_ReturnsErrorDetails() throws Exception {
        Long ontologyId = 21L;
        OntologyMetadataModel metadata = new OntologyMetadataModel();
        metadata.setId(ontologyId);
        metadata.setGraphName("https://example.com/slovnik");

        when(validationConfig.isEnableOntologyViolationDownload()).thenReturn(false);
        when(ontologyService.getOntologyMetadata(ontologyId)).thenReturn(metadata);
        when(validationService.getValidationReport(metadata)).thenReturn(
                new ValidationReportDto(
                        List.of(
                                new ValidationResult(ValidationSeverity.ERROR, "Pojem nemá název", "rule-nazev",
                                        "https://example.com/pojem/1", "http://www.w3.org/2004/02/skos/core#prefLabel", null),
                                new ValidationResult(ValidationSeverity.WARNING, "Doporučujeme popis", "rule-popis",
                                        "https://example.com/pojem/2", null, null)),
                        metadata.getGraphName(),
                        Instant.now()));

        mockMvc.perform(get("/api/ontology/{ontologyId}/download", ontologyId)
                        .param("format", "json-ld"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("ONTOLOGY_DOWNLOAD_BLOCKED_BY_VALIDATION"))
                .andExpect(jsonPath("$.message").value("Slovník nelze stáhnout, protože obsahuje 1 chybu z kontroly. Opravte je a spusťte kontrolu znovu."))
                .andExpect(jsonPath("$.data.graphName").value("https://example.com/slovnik"))
                .andExpect(jsonPath("$.data.errorCount").value(1))
                .andExpect(jsonPath("$.data.truncated").value(false))
                // only the ERROR is listed; the WARNING does not block and is not reported here
                .andExpect(jsonPath("$.data.errors.length()").value(1))
                .andExpect(jsonPath("$.data.errors[0].ruleName").value("rule-nazev"))
                .andExpect(jsonPath("$.data.errors[0].message").value("Pojem nemá název"))
                .andExpect(jsonPath("$.data.errors[0].focusNodeUri").value("https://example.com/pojem/1"));

        verify(ontologyDownloadService, never()).downloadOntology(anyLong(), anyString());
    }

    @Test
    void testDownloadOntology_BlockedWithManyErrors_TruncatesListButKeepsFullCount() throws Exception {
        Long ontologyId = 21L;
        OntologyMetadataModel metadata = new OntologyMetadataModel();
        metadata.setId(ontologyId);
        metadata.setGraphName("https://example.com/slovnik");

        List<ValidationResult> results = IntStream.range(0, 38)
                .mapToObj(i -> new ValidationResult(ValidationSeverity.ERROR, "Chyba " + i, "rule-" + i,
                        "https://example.com/pojem/" + i, null, null))
                .collect(Collectors.toList());

        when(validationConfig.isEnableOntologyViolationDownload()).thenReturn(false);
        when(ontologyService.getOntologyMetadata(ontologyId)).thenReturn(metadata);
        when(validationService.getValidationReport(metadata)).thenReturn(
                new ValidationReportDto(results, metadata.getGraphName(), Instant.now()));

        mockMvc.perform(get("/api/ontology/{ontologyId}/download", ontologyId)
                        .param("format", "json-ld"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ONTOLOGY_DOWNLOAD_BLOCKED_BY_VALIDATION"))
                .andExpect(jsonPath("$.message").value("Slovník nelze stáhnout, protože obsahuje 38 chyb z kontroly. Opravte je a spusťte kontrolu znovu."))
                .andExpect(jsonPath("$.data.errorCount").value(38))
                .andExpect(jsonPath("$.data.truncated").value(true))
                .andExpect(jsonPath("$.data.errors.length()").value(20));
    }

    @Test
    void testDownloadOntology_WarningsOnly_IsNotBlocked() throws Exception {
        Long ontologyId = 1L;
        String ttlContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> .";
        OntologyMetadataModel metadata = new OntologyMetadataModel();
        metadata.setId(ontologyId);
        metadata.setGraphName("https://example.com/slovnik");

        when(validationConfig.isEnableOntologyViolationDownload()).thenReturn(false);
        when(ontologyService.getOntologyMetadata(ontologyId)).thenReturn(metadata);
        when(validationService.getValidationReport(metadata)).thenReturn(
                new ValidationReportDto(
                        List.of(new ValidationResult(ValidationSeverity.WARNING, "Doporučujeme popis", "rule-popis",
                                "https://example.com/pojem/2", null, null)),
                        metadata.getGraphName(),
                        Instant.now()));
        when(ontologyDownloadService.downloadOntology(ontologyId, "ttl")).thenReturn(ttlContent);

        mockMvc.perform(get("/api/ontology/{ontologyId}/download", ontologyId)
                        .param("format", "ttl"))
                .andExpect(status().isOk())
                .andExpect(content().string(ttlContent));
    }

    @Test
    void testDownloadOntology_ErrorsAllowedByConfig_IsNotBlocked() throws Exception {
        Long ontologyId = 21L;
        String ttlContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> .";

        when(validationConfig.isEnableOntologyViolationDownload()).thenReturn(true);
        when(ontologyDownloadService.downloadOntology(ontologyId, "ttl")).thenReturn(ttlContent);

        mockMvc.perform(get("/api/ontology/{ontologyId}/download", ontologyId)
                        .param("format", "ttl"))
                .andExpect(status().isOk())
                .andExpect(content().string(ttlContent));

        // the report is not even read when downloads with errors are permitted
        verify(validationService, never()).getValidationReport(any());
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

    // ========== Get Validation Report Tests ==========

    @Test
    void testGetValidationReport_Success() throws Exception {
        String slug = "test-ontology";
        OntologyMetadataModel metadataModel = new OntologyMetadataModel();
        metadataModel.setSlug(slug);
        metadataModel.setGraphName("http://example.org/test-ontology");

        java.time.Instant timestamp = java.time.Instant.parse("2026-08-24T10:15:30Z");
        com.dia.validation.ValidationResult result = new com.dia.validation.ValidationResult(
                com.dia.validation.ValidationSeverity.ERROR,
                "Chybí název pojmu",
                "rule-name-required",
                "http://example.org/test-ontology/pojem/1",
                "http://www.w3.org/2004/02/skos/core#prefLabel",
                null);

        when(ontologyService.getOntologyMetadataBySlug(slug)).thenReturn(metadataModel);
        when(validationService.getValidationReportOrEmpty(metadataModel)).thenReturn(
                new com.dia.validation.ValidationReportDto(List.of(result), metadataModel.getGraphName(), timestamp));

        mockMvc.perform(get("/api/ontology/{slug}/validation-report", slug))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.results.length()").value(1))
                .andExpect(jsonPath("$.data.results[0].severity").value("ERROR"))
                .andExpect(jsonPath("$.data.results[0].message").value("Chybí název pojmu"))
                .andExpect(jsonPath("$.data.ontologyIri").value("http://example.org/test-ontology"))
                .andExpect(jsonPath("$.data.timestamp").exists());
    }

    /** Never validated is a state, not an error — an empty report, not a 404. */
    @Test
    void testGetValidationReport_NeverValidatedReturnsEmptyReport() throws Exception {
        String slug = "test-ontology";
        OntologyMetadataModel metadataModel = new OntologyMetadataModel();
        metadataModel.setSlug(slug);
        metadataModel.setGraphName("http://example.org/test-ontology");

        when(ontologyService.getOntologyMetadataBySlug(slug)).thenReturn(metadataModel);
        when(validationService.getValidationReportOrEmpty(metadataModel)).thenReturn(
                new com.dia.validation.ValidationReportDto(List.of(), metadataModel.getGraphName(), null));

        mockMvc.perform(get("/api/ontology/{slug}/validation-report", slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(0))
                .andExpect(jsonPath("$.data.ontologyIri").value("http://example.org/test-ontology"))
                .andExpect(jsonPath("$.data.timestamp").doesNotExist());
    }

    /** The report is keyed off the ontology, so an unknown slug 404s before the report lookup. */
    @Test
    void testGetValidationReport_OntologyNotFound() throws Exception {
        String slug = "non-existent-ontology";

        when(ontologyService.getOntologyMetadataBySlug(slug))
                .thenThrow(new OntologyNotFoundException("Ontology not found"));

        mockMvc.perform(get("/api/ontology/{slug}/validation-report", slug))
                .andExpect(status().isNotFound());

        verify(validationService, never()).getValidationReportOrEmpty(any());
    }

    /**
     * The tool's own ValidationException has its own handler — it must surface its message, not
     * fall through to the generic catch-all's masked "Nastala neočekávaná chyba."
     */
    @Test
    void testGetValidationReport_ReportLookupFailureIsHandled() throws Exception {
        String slug = "test-ontology";
        OntologyMetadataModel metadataModel = new OntologyMetadataModel();
        metadataModel.setSlug(slug);
        metadataModel.setGraphName("http://example.org/test-ontology");

        when(ontologyService.getOntologyMetadataBySlug(slug)).thenReturn(metadataModel);
        when(validationService.getValidationReportOrEmpty(metadataModel))
                .thenThrow(new com.dia.ismdtoolbackend.exception.ValidationException(
                        "Během načítání zprávy z kontroly došlo k chybě", new RuntimeException("DB down")));

        mockMvc.perform(get("/api/ontology/{slug}/validation-report", slug))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Během načítání zprávy z kontroly došlo k chybě"));
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
    void testValidateOntology_ServiceUnavailable_returns503() throws Exception {
        String slug = "test-ontology";

        OntologyMetadataModel ontologyMetadata = new OntologyMetadataModel();
        ontologyMetadata.setId(1L);
        ontologyMetadata.setGraphName("http://example.org/test-ontology");
        ontologyMetadata.setSlug(slug);

        TestOntologySecurityService.setAllowModify(true);
        when(ontologyService.getTtlContentFromOntology(any()))
                .thenReturn("@prefix owl: <http://www.w3.org/2002/07/owl#> .");
        // Validator down → client throws the unavailable exception → 503.
        when(validationClient.requestValidation(anyString(), anyString()))
                .thenThrow(new com.dia.ismdtoolbackend.exception.ValidationServiceUnavailableException(
                        "Validační služba", "validator unreachable"));

        mockMvc.perform(post("/api/ontology/{slug}/validate", slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ontologyMetadata)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Validační služba není momentálně dostupná."));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testValidateOntology_ValidatorRejectedInput_returns400WithValidatorMessage() throws Exception {
        String slug = "test-ontology";

        OntologyMetadataModel ontologyMetadata = new OntologyMetadataModel();
        ontologyMetadata.setId(1L);
        ontologyMetadata.setGraphName("http://example.org/test-ontology");
        ontologyMetadata.setSlug(slug);

        TestOntologySecurityService.setAllowModify(true);
        when(ontologyService.getTtlContentFromOntology(any()))
                .thenReturn("bad ttl");
        // Validator answered with a 4xx → client throws a rejection carrying the validator's message → 400.
        when(validationClient.requestValidation(anyString(), anyString()))
                .thenThrow(new com.dia.ismdtoolbackend.exception.OntologyValidationException(
                        "Invalid TTL syntax: line 3"));

        mockMvc.perform(post("/api/ontology/{slug}/validate", slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ontologyMetadata)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Invalid TTL syntax: line 3"));
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
    @Disabled("catalog-record endpoint deprecated and hidden from OpenAPI")
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
    @Disabled("catalog-record endpoint deprecated and hidden from OpenAPI")
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
    @Disabled("catalog-record endpoint deprecated and hidden from OpenAPI")
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
    @Disabled("catalog-record endpoint deprecated and hidden from OpenAPI")
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

    // ========== Get Concepts By IRI Tests ==========

    @Test
    void testGetConceptsByIri_IsmdSuccess_includesSlug() throws Exception {
        String iri = "http://example.org/test-ontology";

        MinimalConceptDto concept = MinimalConceptDto.builder()
                .iri(iri + "/pojem/foo")
                .slug("foo")
                .name(java.util.Map.of("cs", "Foo"))
                .conceptType(com.dia.ismdtoolbackend.enums.ConceptType.TRIDA)
                .build();

        when(ontologyService.getConceptsByIri(iri, SearchSource.ISMD))
                .thenReturn(java.util.List.of(concept));

        mockMvc.perform(get("/api/ontology/concepts")
                        .param("iri", iri)
                        .param("source", "ISMD"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].iri").value(iri + "/pojem/foo"))
                .andExpect(jsonPath("$.data[0].slug").value("foo"))
                .andExpect(jsonPath("$.data[0].name.cs").value("Foo"))
                .andExpect(jsonPath("$.data[0].conceptType").value("TRIDA"))
                .andExpect(jsonPath("$.message").value("Seznam pojmů byl úspěšně načten."));
    }

    @Test
    void testGetConceptsByIri_NkdSuccess_omitsSlug() throws Exception {
        String iri = "https://data.gov.cz/zdroj/slovnik/test";

        MinimalConceptDto concept = MinimalConceptDto.builder()
                .iri(iri + "/pojem/bar")
                .name(java.util.Map.of("cs", "Bar"))
                .conceptType(com.dia.ismdtoolbackend.enums.ConceptType.VZTAH)
                .build();

        when(ontologyService.getConceptsByIri(iri, SearchSource.NKD))
                .thenReturn(java.util.List.of(concept));

        mockMvc.perform(get("/api/ontology/concepts")
                        .param("iri", iri)
                        .param("source", "NKD"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].iri").value(iri + "/pojem/bar"))
                .andExpect(jsonPath("$.data[0].name.cs").value("Bar"))
                .andExpect(jsonPath("$.data[0].conceptType").value("VZTAH"))
                // FE uses IRI for NKD navigation — slug must be omitted (NON_NULL).
                .andExpect(jsonPath("$.data[0].slug").doesNotExist())
                .andExpect(jsonPath("$.message").value("Seznam pojmů byl úspěšně načten."));
    }

    @Test
    void testGetConceptsByIri_EmptyListCoercedToEmptyArray() throws Exception {
        String iri = "https://data.gov.cz/zdroj/slovnik/test";

        when(ontologyService.getConceptsByIri(iri, SearchSource.NKD))
                .thenReturn(java.util.List.of());

        mockMvc.perform(get("/api/ontology/concepts")
                        .param("iri", iri)
                        .param("source", "NKD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void testGetConceptsByIri_ServiceNotFoundRelayed() throws Exception {
        String iri = "http://example.org/missing";

        when(ontologyService.getConceptsByIri(iri, SearchSource.ISMD))
                .thenThrow(new org.apache.jena.ontology.OntologyException(
                        "Slovník s IRI " + iri + " nebyl nalezen."));

        mockMvc.perform(get("/api/ontology/concepts")
                        .param("iri", iri)
                        .param("source", "ISMD"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Slovník s IRI " + iri + " nebyl nalezen."));
    }

    @Test
    void testGetConceptsByIri_NkdNotFoundRelayed() throws Exception {
        String iri = "https://data.gov.cz/zdroj/slovnik/missing";

        when(ontologyService.getConceptsByIri(iri, SearchSource.NKD))
                .thenThrow(new NkdResourceNotFoundException(
                        "Slovník s IRI " + iri + " nebyl v NKD nalezen."));

        mockMvc.perform(get("/api/ontology/concepts")
                        .param("iri", iri)
                        .param("source", "NKD"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Slovník s IRI " + iri + " nebyl v NKD nalezen."));
    }

    @Test
    void testGetConceptsByIri_UnsupportedSourceRelayed() throws Exception {
        String iri = "http://example.org/x";

        when(ontologyService.getConceptsByIri(iri, SearchSource.UNPUBLISHED))
                .thenThrow(new IllegalArgumentException(
                        "Nepodporovaný zdroj: UNPUBLISHED. Povolené hodnoty: ISMD, NKD."));

        mockMvc.perform(get("/api/ontology/concepts")
                        .param("iri", iri)
                        .param("source", "UNPUBLISHED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void testGetConceptsByIri_UnknownSourceRejected() throws Exception {
        mockMvc.perform(get("/api/ontology/concepts")
                        .param("iri", "http://example.org/x")
                        .param("source", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void testGetConceptsByIri_MissingSourceParamRejected() throws Exception {
        mockMvc.perform(get("/api/ontology/concepts")
                        .param("iri", "http://example.org/x"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testGetConceptsByIri_MissingIriParamRejected() throws Exception {
        mockMvc.perform(get("/api/ontology/concepts")
                        .param("source", "ISMD"))
                .andExpect(status().isBadRequest());
    }
}
