package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.client.ValidationClient;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.OntologyUploadException;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.service.impl.OntologyUploadServiceImpl;
import com.dia.ismdtoolbackend.utility.published.PublishedResourceUtil;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for transactional boundary edge cases in OntologyUploadServiceImpl:
 * - TDB2 cleanup on metadata save failure
 * - Exception propagation with proper messages
 * - Model resource cleanup on failure paths
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OntologyUploadTransactionalTest {

    @Mock
    private OntologyMetadataMapper ontologyMetadataMapper;

    @Mock
    private OntologyMetadataRepository ontologyMetadataRepository;

    @Mock
    private ConceptMetadataRepository conceptMetadataRepository;

    @Mock
    private ValidationClient validationClient;

    @Mock
    private ValidationReportRepository validationReportRepository;

    @Mock
    private JenaTDB2Repository jenaTDB2Repository;

    @Mock
    private NkdSparqlClient nkdSparqlClient;

    @Mock
    private PublishedResourceUtil deviationChecker;

    @InjectMocks
    private OntologyUploadServiceImpl uploadService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(uploadService, "maxFileSizeConfig", "10MB");
        ReflectionTestUtils.setField(uploadService, "rdfParsingTimeoutSeconds", 60);
    }

    private MultipartFile createTurtleFile(String content) throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn((long) content.length());
        when(file.getOriginalFilename()).thenReturn("test-ontology.ttl");
        when(file.getContentType()).thenReturn("text/turtle");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(bytes));
        when(file.getBytes()).thenReturn(bytes);
        return file;
    }

    @Test
    void uploadFromFile_tdb2SaveFails_shouldThrowWithoutMetadata() throws IOException {
        String ttl = "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n<https://example.com/ontology> a owl:Ontology .";
        MultipartFile file = createTurtleFile(ttl);

        when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(deviationChecker.checkPublishedResourcesInNKD(any())).thenReturn(Collections.emptyList());
        doThrow(new RuntimeException("TDB2 connection failed"))
                .when(jenaTDB2Repository).putOntologyModel(anyString(), any());

        OntologyUploadException thrown = assertThrows(OntologyUploadException.class,
                () -> uploadService.uploadFromFile(file, "user1"));

        assertTrue(thrown.getMessage().contains("TDB2"));
        // Metadata repository should not have been called
        verify(ontologyMetadataRepository, never()).save(any());
    }

    @Test
    void uploadFromFile_metadataSaveFails_shouldCleanupTDB2() throws IOException {
        String ttl = "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n<https://example.com/ontology> a owl:Ontology .";
        MultipartFile file = createTurtleFile(ttl);

        when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(deviationChecker.checkPublishedResourcesInNKD(any())).thenReturn(Collections.emptyList());
        // TDB2 save succeeds
        doNothing().when(jenaTDB2Repository).putOntologyModel(anyString(), any());
        // Metadata save fails
        when(ontologyMetadataRepository.save(any())).thenThrow(new RuntimeException("DB constraint violation"));

        OntologyUploadException thrown = assertThrows(OntologyUploadException.class,
                () -> uploadService.uploadFromFile(file, "user1"));

        // TDB2 should be cleaned up
        verify(jenaTDB2Repository).deleteGraph(anyString());
        assertTrue(thrown.getMessage().contains("Failed to upload ontology"));
    }

    @Test
    void uploadFromFile_tdb2CleanupAlsoFails_shouldStillThrowOriginalException() throws IOException {
        String ttl = "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n<https://example.com/ontology> a owl:Ontology .";
        MultipartFile file = createTurtleFile(ttl);

        when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(deviationChecker.checkPublishedResourcesInNKD(any())).thenReturn(Collections.emptyList());
        doNothing().when(jenaTDB2Repository).putOntologyModel(anyString(), any());
        when(ontologyMetadataRepository.save(any())).thenThrow(new RuntimeException("DB failure"));
        // TDB2 cleanup also fails
        doThrow(new RuntimeException("TDB2 cleanup failed"))
                .when(jenaTDB2Repository).deleteGraph(anyString());

        OntologyUploadException thrown = assertThrows(OntologyUploadException.class,
                () -> uploadService.uploadFromFile(file, "user1"));

        // Original exception should still propagate
        assertTrue(thrown.getMessage().contains("Failed to upload ontology"));
        verify(jenaTDB2Repository).deleteGraph(anyString());
    }

    @Test
    void uploadFromFile_conceptMetadataExtractionFails_shouldCleanupTDB2() throws IOException {
        String ttl = "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n<https://example.com/ontology> a owl:Ontology .";
        MultipartFile file = createTurtleFile(ttl);

        OntologyMetadataEntity savedEntity = new OntologyMetadataEntity();
        savedEntity.setId(1L);
        savedEntity.setSlug("test-ontology");
        savedEntity.setGraphName("https://example.com/ontology");

        OntologyMetadataModel model = new OntologyMetadataModel();
        model.setId(1L);
        model.setSlug("test-ontology");
        model.setGraphName("https://example.com/ontology");

        when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(deviationChecker.checkPublishedResourcesInNKD(any())).thenReturn(Collections.emptyList());
        doNothing().when(jenaTDB2Repository).putOntologyModel(anyString(), any());
        when(ontologyMetadataRepository.save(any())).thenReturn(savedEntity);
        when(ontologyMetadataMapper.toDto(any(OntologyMetadataEntity.class))).thenReturn(model);
        // Concept metadata extraction fails
        doThrow(new RuntimeException("Concept extraction error"))
                .when(conceptMetadataRepository).saveAll(any());

        OntologyUploadException thrown = assertThrows(OntologyUploadException.class,
                () -> uploadService.uploadFromFile(file, "user1"));

        verify(jenaTDB2Repository).deleteGraph(anyString());
    }
}
