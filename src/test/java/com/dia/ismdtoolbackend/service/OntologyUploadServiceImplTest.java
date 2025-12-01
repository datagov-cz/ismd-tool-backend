package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.utility.analyzer.AnalysisResult;
import com.dia.ismdtoolbackend.utility.analyzer.OntologyAnalyzer;
import com.dia.ismdtoolbackend.client.ValidationClient;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.models.UserModel;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.service.impl.OntologyUploadServiceImpl;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.riot.Lang;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OntologyUploadServiceImplTest {

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
    private OntologyAnalyzer ontologyAnalyzer;

    @Mock
    private JenaTDB2Repository jenaTDB2Repository;

    @Mock
    private MultipartFile multipartFile;

    @Mock
    private NkdSparqlClient nkdSparqlClient;

    @InjectMocks
    private OntologyUploadServiceImpl ontologyUploadService;

    @BeforeEach
    void setUp() {
        ontologyUploadService = new OntologyUploadServiceImpl(
                ontologyMetadataMapper,
                ontologyMetadataRepository,
                conceptMetadataRepository,
                validationClient,
                validationReportRepository,
                ontologyAnalyzer,
                jenaTDB2Repository,
                nkdSparqlClient
        );
    }

    @Test
    void testDetermineRDFFormat_TTLExtension() {
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");
        
        Lang result = ontologyUploadService.determineRDFFormat(multipartFile);
        
        assertEquals(Lang.TURTLE, result);
    }

    @Test
    void testDetermineRDFFormat_TurtleExtension() {
        when(multipartFile.getOriginalFilename()).thenReturn("test.turtle");
        
        Lang result = ontologyUploadService.determineRDFFormat(multipartFile);
        
        assertEquals(Lang.TURTLE, result);
    }

    @Test
    void testDetermineRDFFormat_JsonLdExtension() {
        when(multipartFile.getOriginalFilename()).thenReturn("test.jsonld");
        
        Lang result = ontologyUploadService.determineRDFFormat(multipartFile);
        
        assertEquals(Lang.JSONLD, result);
    }

    @Test
    void testDetermineRDFFormat_JsonLdAlternativeExtension() {
        when(multipartFile.getOriginalFilename()).thenReturn("test.json-ld");
        
        Lang result = ontologyUploadService.determineRDFFormat(multipartFile);
        
        assertEquals(Lang.JSONLD, result);
    }

    @Test
    void testDetermineRDFFormat_ByContentType_Turtle() {
        when(multipartFile.getOriginalFilename()).thenReturn("test.unknown");
        when(multipartFile.getContentType()).thenReturn("text/turtle");
        
        Lang result = ontologyUploadService.determineRDFFormat(multipartFile);
        
        assertEquals(Lang.TURTLE, result);
    }

    @Test
    void testDetermineRDFFormat_ByContentType_Json() {
        when(multipartFile.getOriginalFilename()).thenReturn("test.unknown");
        when(multipartFile.getContentType()).thenReturn("application/json");
        
        Lang result = ontologyUploadService.determineRDFFormat(multipartFile);
        
        assertEquals(Lang.JSONLD, result);
    }

    @Test
    void testDetermineRDFFormat_UnknownFormat() {
        when(multipartFile.getOriginalFilename()).thenReturn("test.unknown");
        when(multipartFile.getContentType()).thenReturn("application/unknown");
        
        Lang result = ontologyUploadService.determineRDFFormat(multipartFile);
        
        assertNull(result);
    }

    @Test
    void testDetermineRDFFormat_NoFilename() {
        when(multipartFile.getOriginalFilename()).thenReturn(null);
        when(multipartFile.getContentType()).thenReturn("text/turtle");
        
        Lang result = ontologyUploadService.determineRDFFormat(multipartFile);
        
        assertEquals(Lang.TURTLE, result);
    }

    @Test
    void testUploadFromFile_WithProvidedName() throws IOException {
        String providedName = "custom-ontology";
        String userId = "user123";
        byte[] fileContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> . <http://example.org/test> a owl:Ontology .".getBytes();

        when(multipartFile.getBytes()).thenReturn(fileContent);
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");

        OntologyMetadataModel expectedDto = new OntologyMetadataModel();
        expectedDto.setGraphName(providedName);
        expectedDto.setUser(new UserModel(userId));

        OntologyMetadataEntity savedEntity = new OntologyMetadataEntity();
        savedEntity.setGraphName(providedName);
        savedEntity.setUserId(userId);

        when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(ontologyMetadataMapper.toEntity(any(OntologyMetadataModel.class))).thenReturn(savedEntity);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(savedEntity);
        when(ontologyMetadataMapper.toDto(any(OntologyMetadataEntity.class))).thenReturn(expectedDto);

        AnalysisResult mockAnalysisResult = new AnalysisResult(Set.of(), Set.of());
        when(ontologyAnalyzer.analyzeUploadedOntology(any(OntModel.class))).thenReturn(mockAnalysisResult);

        doNothing().when(jenaTDB2Repository).putOntologyModel(eq(providedName), any(OntModel.class));

        OntologyMetadataModel result = ontologyUploadService.uploadFromFile(multipartFile, providedName, Lang.TURTLE, userId);

        assertNotNull(result);
        assertEquals(providedName, result.getGraphName());
        assertEquals(userId, result.getUser().getUserId());

        verify(jenaTDB2Repository).putOntologyModel(eq(providedName), any(OntModel.class));
        verify(ontologyMetadataRepository).save(any(OntologyMetadataEntity.class));
    }

    @Test
    void testUploadFromFile_WithOntologyIRI() throws IOException {
        String userId = "user123";
        String ontologyIRI = "http://example.org/test-ontology";
        byte[] fileContent = String.format("@prefix owl: <http://www.w3.org/2002/07/owl#> . <%s> a owl:Ontology .", ontologyIRI).getBytes();

        when(multipartFile.getBytes()).thenReturn(fileContent);
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");

        OntologyMetadataModel expectedDto = new OntologyMetadataModel();
        expectedDto.setGraphName(ontologyIRI);
        expectedDto.setUser(new UserModel(userId));

        OntologyMetadataEntity savedEntity = new OntologyMetadataEntity();
        savedEntity.setGraphName(ontologyIRI);
        savedEntity.setUserId(userId);

        when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(ontologyMetadataMapper.toEntity(any(OntologyMetadataModel.class))).thenReturn(savedEntity);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(savedEntity);
        when(ontologyMetadataMapper.toDto(any(OntologyMetadataEntity.class))).thenReturn(expectedDto);

        AnalysisResult mockAnalysisResult = new AnalysisResult(Set.of(), Set.of());
        when(ontologyAnalyzer.analyzeUploadedOntology(any(OntModel.class))).thenReturn(mockAnalysisResult);

        doNothing().when(jenaTDB2Repository).putOntologyModel(eq(ontologyIRI), any(OntModel.class));

        OntologyMetadataModel result = ontologyUploadService.uploadFromFile(multipartFile, null, Lang.TURTLE, userId);

        assertNotNull(result);
        assertEquals(ontologyIRI, result.getGraphName());
        assertEquals(userId, result.getUser().getUserId());

        verify(jenaTDB2Repository).putOntologyModel(eq(ontologyIRI), any(OntModel.class));
    }

    @Test
    void testUploadFromFile_GeneratedName() throws IOException {
        String userId = "user123";
        String filename = "test-ontology.ttl";
        byte[] fileContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> .".getBytes();

        when(multipartFile.getBytes()).thenReturn(fileContent);
        when(multipartFile.getOriginalFilename()).thenReturn(filename);

        OntologyMetadataModel expectedDto = new OntologyMetadataModel();
        OntologyMetadataEntity savedEntity = new OntologyMetadataEntity();

        when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(ontologyMetadataMapper.toEntity(any(OntologyMetadataModel.class))).thenReturn(savedEntity);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(savedEntity);
        when(ontologyMetadataMapper.toDto(any(OntologyMetadataEntity.class))).thenReturn(expectedDto);

        AnalysisResult mockAnalysisResult = new AnalysisResult(Set.of(), Set.of());
        when(ontologyAnalyzer.analyzeUploadedOntology(any(OntModel.class))).thenReturn(mockAnalysisResult);

        doNothing().when(jenaTDB2Repository).putOntologyModel(anyString(), any(OntModel.class));

        OntologyMetadataModel result = ontologyUploadService.uploadFromFile(multipartFile, null, Lang.TURTLE, userId);

        assertNotNull(result);
        verify(jenaTDB2Repository).putOntologyModel(argThat(graphName ->
            graphName.contains("test-ontology") && graphName.startsWith("https://slovník.gov.cz/")
        ), any(OntModel.class));
    }

    @Test
    void testUploadFromFile_EmptyProvidedName() throws IOException {
        String userId = "user123";
        String filename = "test.ttl";
        byte[] fileContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> .".getBytes();

        when(multipartFile.getBytes()).thenReturn(fileContent);
        when(multipartFile.getOriginalFilename()).thenReturn(filename);

        OntologyMetadataModel expectedDto = new OntologyMetadataModel();
        OntologyMetadataEntity savedEntity = new OntologyMetadataEntity();

        when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(ontologyMetadataMapper.toEntity(any(OntologyMetadataModel.class))).thenReturn(savedEntity);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(savedEntity);
        when(ontologyMetadataMapper.toDto(any(OntologyMetadataEntity.class))).thenReturn(expectedDto);

        AnalysisResult mockAnalysisResult = new AnalysisResult(Set.of(), Set.of());
        when(ontologyAnalyzer.analyzeUploadedOntology(any(OntModel.class))).thenReturn(mockAnalysisResult);

        doNothing().when(jenaTDB2Repository).putOntologyModel(anyString(), any(OntModel.class));

        OntologyMetadataModel result = ontologyUploadService.uploadFromFile(multipartFile, "  ", Lang.TURTLE, userId);

        assertNotNull(result);
        verify(jenaTDB2Repository).putOntologyModel(argThat(graphName ->
            graphName.contains("test") && graphName.startsWith("https://slovník.gov.cz/")
        ), any(OntModel.class));
    }

    @Test
    void testUploadFromFile_IOExceptionHandling() throws IOException {
        String userId = "user123";
        
        when(multipartFile.getBytes()).thenThrow(new IOException("File read error"));
        
        assertThrows(IOException.class, () -> ontologyUploadService.uploadFromFile(multipartFile, "test", Lang.TURTLE, userId));
        
        verify(ontologyMetadataRepository, never()).save(any());
    }

    @Test
    void testUploadFromFile_NoFilename() throws IOException {
        String userId = "user123";
        byte[] fileContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> .".getBytes();

        when(multipartFile.getBytes()).thenReturn(fileContent);
        when(multipartFile.getOriginalFilename()).thenReturn(null);

        OntologyMetadataModel expectedDto = new OntologyMetadataModel();
        OntologyMetadataEntity savedEntity = new OntologyMetadataEntity();

        when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(ontologyMetadataMapper.toEntity(any(OntologyMetadataModel.class))).thenReturn(savedEntity);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(savedEntity);
        when(ontologyMetadataMapper.toDto(any(OntologyMetadataEntity.class))).thenReturn(expectedDto);

        AnalysisResult mockAnalysisResult = new AnalysisResult(Set.of(), Set.of());
        when(ontologyAnalyzer.analyzeUploadedOntology(any(OntModel.class))).thenReturn(mockAnalysisResult);

        doNothing().when(jenaTDB2Repository).putOntologyModel(anyString(), any(OntModel.class));

        OntologyMetadataModel result = ontologyUploadService.uploadFromFile(multipartFile, null, Lang.TURTLE, userId);

        assertNotNull(result);
        verify(jenaTDB2Repository).putOntologyModel(argThat(graphName ->
            graphName.contains("ontology") && graphName.startsWith("https://slovník.gov.cz/")
        ), any(OntModel.class));
    }
}