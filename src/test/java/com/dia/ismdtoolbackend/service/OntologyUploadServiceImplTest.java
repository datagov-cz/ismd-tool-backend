package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.exception.EmptyFileException;
import com.dia.ismdtoolbackend.exception.OntologyUploadException;
import com.dia.ismdtoolbackend.exception.UnsupportedRdfFormatException;
import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.client.ValidationClient;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.models.UserModel;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.service.impl.OntologyUploadServiceImpl;
import com.dia.ismdtoolbackend.utility.published.PublishedResourceUtil;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.riot.Lang;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

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
    private JenaTDB2Repository jenaTDB2Repository;

    @Mock
    private MultipartFile multipartFile;

    @Mock
    private NkdSparqlClient nkdSparqlClient;

    @Mock
    private PublishedResourceUtil publishedResourceUtil;

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
                jenaTDB2Repository,
                publishedResourceUtil
        );
        ReflectionTestUtils.setField(ontologyUploadService, "maxFileSizeConfig", "10MB");
        ReflectionTestUtils.setField(ontologyUploadService, "rdfParsingTimeoutSeconds", 60);
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
    void testUploadFromFile_WithOntologyIRI() throws Exception {
        String userId = "user123";
        String ontologyIRI = "http://example.org/test-ontology";
        byte[] fileContent = String.format("@prefix owl: <http://www.w3.org/2002/07/owl#> . <%s> a owl:Ontology .", ontologyIRI).getBytes();

        when(multipartFile.getBytes()).thenReturn(fileContent);
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");
        when(multipartFile.isEmpty()).thenReturn(false);

        OntologyMetadataModel expectedDto = new OntologyMetadataModel();
        expectedDto.setGraphName(ontologyIRI);
        expectedDto.setUser(new UserModel(userId));
        expectedDto.setId(1L);

        OntologyMetadataEntity savedEntity = new OntologyMetadataEntity();
        savedEntity.setGraphName(ontologyIRI);
        savedEntity.setUserId(userId);
        savedEntity.setId(1L);

        when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(ontologyMetadataMapper.toEntity(any(OntologyMetadataModel.class))).thenReturn(savedEntity);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(savedEntity);
        when(ontologyMetadataMapper.toDto(any(OntologyMetadataEntity.class))).thenReturn(expectedDto);
        when(ontologyMetadataRepository.findById(1L)).thenReturn(Optional.of(savedEntity));

        when(nkdSparqlClient.getPublishedResourcesList(anyList())).thenReturn(Collections.emptyList());

        doNothing().when(jenaTDB2Repository).putOntologyModel(eq(ontologyIRI), any(OntModel.class));

        OntologyMetadataModel result = ontologyUploadService.uploadFromFile(multipartFile, userId);

        assertNotNull(result);
        assertEquals(ontologyIRI, result.getGraphName());
        assertEquals(userId, result.getUser().getUserId());

        verify(jenaTDB2Repository).putOntologyModel(eq(ontologyIRI), any(OntModel.class));
    }

    @Test
    void testUploadFromFile_NoDerivableOntologyIRI_throws() throws Exception {
        // RDF with no owl:Ontology / skos:ConceptScheme subject — graph name cannot be
        // derived from the data. Per the authority decision, we must FAIL rather than
        // fabricate a filename+UUID name.
        String userId = "user123";
        byte[] fileContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> .".getBytes();

        when(multipartFile.getBytes()).thenReturn(fileContent);
        when(multipartFile.getOriginalFilename()).thenReturn("test-ontology.ttl");
        when(multipartFile.isEmpty()).thenReturn(false);

        when(nkdSparqlClient.getPublishedResourcesList(anyList())).thenReturn(Collections.emptyList());

        assertThrows(OntologyUploadException.class, () ->
                ontologyUploadService.uploadFromFile(multipartFile, userId));

        // Nothing persisted — fail-fast before any TDB2/metadata write.
        // (verifyNoInteractions rather than never().putOntologyModel(..) — the latter
        // makes Mockito toString() the OntModel, which is already closed by the finally.)
        verifyNoInteractions(jenaTDB2Repository);
        verify(ontologyMetadataRepository, never()).save(any());
    }

    @Test
    void testUploadFromFile_NoFilename_noDerivableIRI_throwsWithoutNPE() throws Exception {
        // Null filename + no derivable ontology IRI: must fail cleanly (no NPE from the
        // old filename-fallback path, which no longer exists).
        String userId = "user123";
        byte[] fileContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> .".getBytes();

        when(multipartFile.getBytes()).thenReturn(fileContent);
        when(multipartFile.getOriginalFilename()).thenReturn(null);
        when(multipartFile.isEmpty()).thenReturn(false);
        when(multipartFile.getContentType()).thenReturn("text/turtle");

        when(nkdSparqlClient.getPublishedResourcesList(anyList())).thenReturn(Collections.emptyList());

        assertThrows(OntologyUploadException.class, () ->
                ontologyUploadService.uploadFromFile(multipartFile, userId));

        verifyNoInteractions(jenaTDB2Repository);
        verify(ontologyMetadataRepository, never()).save(any());
    }

    @Test
    void testUploadFromFile_alienConcept_notSavedAsOwned() throws Exception {
        // The vocabulary IRI (graphName) is derived from owl:Ontology. The file embeds
        // TWO slovníky:pojem concepts: one OWNED (under graphName/pojem/...) and one
        // ALIEN (a legislative reference outside graphName's namespace). Only the owned
        // concept may get a Postgres ownership row; the alien must be skipped.
        String userId = "user123";
        String ontologyIRI = "https://slovník.gov.cz/a3791---registr";
        String ownedConcept = ontologyIRI + "/pojem/vysoká-škola";
        String alienConcept = "https://slovník.gov.cz/128-2000/pojem/obec";
        String pojem = "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/pojem";

        String ttl = String.format(
                "@prefix owl: <http://www.w3.org/2002/07/owl#> ."
                        + " <%s> a owl:Ontology ."
                        + " <%s> a <%s> ."
                        + " <%s> a <%s> .",
                ontologyIRI, ownedConcept, pojem, alienConcept, pojem);

        when(multipartFile.getBytes()).thenReturn(ttl.getBytes());
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");
        when(multipartFile.isEmpty()).thenReturn(false);

        OntologyMetadataEntity savedEntity = new OntologyMetadataEntity();
        savedEntity.setGraphName(ontologyIRI);
        savedEntity.setUserId(userId);
        savedEntity.setId(1L);
        OntologyMetadataModel expectedDto = new OntologyMetadataModel();
        expectedDto.setId(1L);

        when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(ontologyMetadataMapper.toEntity(any(OntologyMetadataModel.class))).thenReturn(savedEntity);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(savedEntity);
        when(ontologyMetadataMapper.toDto(any(OntologyMetadataEntity.class))).thenReturn(expectedDto);
        when(ontologyMetadataRepository.findById(1L)).thenReturn(Optional.of(savedEntity));
        when(conceptMetadataRepository.findByConceptIri(anyString())).thenReturn(Optional.empty());
        when(conceptMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(nkdSparqlClient.getPublishedResourcesList(anyList())).thenReturn(Collections.emptyList());
        doNothing().when(jenaTDB2Repository).putOntologyModel(anyString(), any(OntModel.class));

        ontologyUploadService.uploadFromFile(multipartFile, userId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConceptMetadataEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(conceptMetadataRepository).saveAll(captor.capture());
        List<String> savedIris = captor.getValue().stream()
                .map(ConceptMetadataEntity::getConceptIri)
                .toList();

        assertTrue(savedIris.contains(ownedConcept),
                "Owned concept under graphName must be saved as owned");
        assertFalse(savedIris.contains(alienConcept),
                "Alien concept outside graphName's namespace must NOT be claimed as owned");
    }

    @Test
    void testUploadFromFile_IOExceptionHandling() throws Exception {
        String userId = "user123";

        when(multipartFile.isEmpty()).thenReturn(false);
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");
        when(multipartFile.getBytes()).thenThrow(new RuntimeException("File read error"));

        // The implementation catches IOException and wraps it, but RuntimeException propagates directly
        assertThrows(RuntimeException.class, () ->
            ontologyUploadService.uploadFromFile(multipartFile, userId));

        verify(ontologyMetadataRepository, never()).save(any());
    }

    @Test
    void testUploadFromFile_EmptyFile() {
        String userId = "user123";

        when(multipartFile.isEmpty()).thenReturn(true);

        assertThrows(EmptyFileException.class, () ->
            ontologyUploadService.uploadFromFile(multipartFile, userId));

        verify(ontologyMetadataRepository, never()).save(any());
    }

    @Test
    void testUploadFromFile_UnsupportedFormat() {
        String userId = "user123";

        when(multipartFile.isEmpty()).thenReturn(false);
        when(multipartFile.getOriginalFilename()).thenReturn("test.unknown");
        when(multipartFile.getContentType()).thenReturn("application/unknown");

        assertThrows(UnsupportedRdfFormatException.class, () ->
                ontologyUploadService.uploadFromFile(multipartFile, userId));

        verify(ontologyMetadataRepository, never()).save(any());
    }

    @Test
    void testUploadFromFile_NKDCheckBeforeMetadataCreation() throws IOException {
        String graphName = "http://example.org/test-ontology";
        String userId = "user123";
        byte[] fileContent = String.format("@prefix owl: <http://www.w3.org/2002/07/owl#> . <%s> a owl:Ontology .", graphName).getBytes();

        when(multipartFile.getBytes()).thenReturn(fileContent);
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");

        // NKD check returns some published concepts
        List<String> publishedConcepts = List.of("http://example.org/concept1", "http://example.org/concept2");
        when(nkdSparqlClient.getPublishedResourcesList(anyList())).thenReturn(publishedConcepts);

        OntologyMetadataEntity savedEntity = new OntologyMetadataEntity();
        savedEntity.setGraphName(graphName);
        savedEntity.setUserId(userId);
        savedEntity.setId(1L);

        OntologyMetadataModel expectedDto = new OntologyMetadataModel();
        expectedDto.setId(1L);

        when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(ontologyMetadataMapper.toEntity(any(OntologyMetadataModel.class))).thenReturn(savedEntity);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(savedEntity);
        when(ontologyMetadataMapper.toDto(any(OntologyMetadataEntity.class))).thenReturn(expectedDto);
        when(ontologyMetadataRepository.findById(1L)).thenReturn(Optional.of(savedEntity));

        doNothing().when(jenaTDB2Repository).putOntologyModel(anyString(), any(OntModel.class));

        ontologyUploadService.uploadFromFile(multipartFile, userId);

        // Verify metadata save happens
        verify(ontologyMetadataRepository).save(any(OntologyMetadataEntity.class));
    }

    @Test
    void testUploadFromFile_RollbackOnTDB2Failure() throws IOException {
        String ontologyIRI = "http://example.org/test-ontology";
        String userId = "user123";
        byte[] fileContent = String.format("@prefix owl: <http://www.w3.org/2002/07/owl#> . <%s> a owl:Ontology .", ontologyIRI).getBytes();

        when(multipartFile.getBytes()).thenReturn(fileContent);
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");

        when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());

        when(nkdSparqlClient.getPublishedResourcesList(anyList())).thenReturn(Collections.emptyList());

        // Simulate TDB2 failure
        doThrow(new RuntimeException("TDB2 connection failed"))
            .when(jenaTDB2Repository).putOntologyModel(anyString(), any(OntModel.class));

        // Expect exception to be thrown
        assertThrows(OntologyUploadException.class, () ->
            ontologyUploadService.uploadFromFile(multipartFile, userId)
        );

        // TDB2 save fails before any metadata is created — no cleanup needed
        verify(ontologyMetadataRepository, never()).save(any());
        verify(ontologyMetadataRepository, never()).deleteById(anyLong());
        verify(jenaTDB2Repository, never()).deleteGraph(anyString());
    }

    @Test
    void testUploadFromFile_RollbackOnConceptMetadataExtractionFailure() throws IOException {
        String ontologyIRI = "http://example.org/test-ontology";
        String userId = "user123";
        byte[] fileContent = String.format("@prefix owl: <http://www.w3.org/2002/07/owl#> . <%s> a owl:Ontology .", ontologyIRI).getBytes();

        when(multipartFile.getBytes()).thenReturn(fileContent);
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");

        OntologyMetadataEntity savedEntity = new OntologyMetadataEntity();
        savedEntity.setGraphName(ontologyIRI);
        savedEntity.setUserId(userId);
        savedEntity.setId(1L);

        OntologyMetadataModel expectedDto = new OntologyMetadataModel();
        expectedDto.setId(1L);

        when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(ontologyMetadataMapper.toEntity(any(OntologyMetadataModel.class))).thenReturn(savedEntity);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(savedEntity);
        when(ontologyMetadataMapper.toDto(any(OntologyMetadataEntity.class))).thenReturn(expectedDto);

        when(nkdSparqlClient.getPublishedResourcesList(anyList())).thenReturn(Collections.emptyList());

        doNothing().when(jenaTDB2Repository).putOntologyModel(anyString(), any(OntModel.class));

        // Simulate failure when finding metadata for concept extraction
        when(ontologyMetadataRepository.findById(1L)).thenReturn(Optional.empty());

        // Expect exception to be thrown
        assertThrows(Exception.class, () ->
            ontologyUploadService.uploadFromFile(multipartFile, userId)
        );

        // PostgreSQL rollback is handled by @Transactional — no manual deleteById
        verify(ontologyMetadataRepository, never()).deleteById(anyLong());
        // TDB2 graph should be cleaned up since it was saved before the failure
        verify(jenaTDB2Repository).deleteGraph(ontologyIRI);
    }
}