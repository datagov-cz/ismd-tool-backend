package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.entity.dto.OntologyMetadataDto;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.impl.OntologyUploadServiceImpl;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.riot.Lang;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

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
    private MultipartFile multipartFile;

    @Mock
    private RDFConnection rdfConnection;

    @InjectMocks
    private OntologyUploadServiceImpl ontologyUploadService;

    private final String fusekiEndpoint = "http://localhost:3030/test";
/*
    @BeforeEach
    void setUp() {
        ontologyUploadService = new OntologyUploadServiceImpl(
                fusekiEndpoint, 
                ontologyMetadataMapper, 
                ontologyMetadataRepository
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
        
        OntologyMetadataDto expectedDto = new OntologyMetadataDto();
        expectedDto.setGraphName(providedName);
        expectedDto.setUserId(userId);
        
        OntologyMetadataEntity savedEntity = new OntologyMetadataEntity();
        savedEntity.setGraphName(providedName);
        savedEntity.setUserId(userId);
        
        when(ontologyMetadataMapper.toEntity(any(OntologyMetadataDto.class))).thenReturn(savedEntity);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(savedEntity);
        when(ontologyMetadataMapper.toDto(any(OntologyMetadataEntity.class))).thenReturn(expectedDto);

        try (MockedStatic<RDFConnection> mockedRDFConnection = mockStatic(RDFConnection.class)) {
            mockedRDFConnection.when(() -> RDFConnection.connect(fusekiEndpoint)).thenReturn(rdfConnection);
            
            OntologyMetadataDto result = ontologyUploadService.uploadFromFile(multipartFile, providedName, Lang.TURTLE, userId);
            
            assertNotNull(result);
            assertEquals(providedName, result.getGraphName());
            assertEquals(userId, result.getUserId());
            
            verify(rdfConnection).put(eq(providedName), any(OntModel.class));
            verify(rdfConnection).close();
            verify(ontologyMetadataRepository).save(any(OntologyMetadataEntity.class));
        }
    }

    @Test
    void testUploadFromFile_WithOntologyIRI() throws IOException {
        String userId = "user123";
        String ontologyIRI = "http://example.org/test-ontology";
        byte[] fileContent = String.format("@prefix owl: <http://www.w3.org/2002/07/owl#> . <%s> a owl:Ontology .", ontologyIRI).getBytes();
        
        when(multipartFile.getBytes()).thenReturn(fileContent);
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");
        
        OntologyMetadataDto expectedDto = new OntologyMetadataDto();
        expectedDto.setGraphName(ontologyIRI);
        expectedDto.setUserId(userId);
        
        OntologyMetadataEntity savedEntity = new OntologyMetadataEntity();
        savedEntity.setGraphName(ontologyIRI);
        savedEntity.setUserId(userId);
        
        when(ontologyMetadataMapper.toEntity(any(OntologyMetadataDto.class))).thenReturn(savedEntity);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(savedEntity);
        when(ontologyMetadataMapper.toDto(any(OntologyMetadataEntity.class))).thenReturn(expectedDto);

        try (MockedStatic<RDFConnection> mockedRDFConnection = mockStatic(RDFConnection.class)) {
            mockedRDFConnection.when(() -> RDFConnection.connect(fusekiEndpoint)).thenReturn(rdfConnection);
            
            OntologyMetadataDto result = ontologyUploadService.uploadFromFile(multipartFile, null, Lang.TURTLE, userId);
            
            assertNotNull(result);
            assertEquals(ontologyIRI, result.getGraphName());
            assertEquals(userId, result.getUserId());
            
            verify(rdfConnection).put(eq(ontologyIRI), any(OntModel.class));
        }
    }

    @Test
    void testUploadFromFile_GeneratedName() throws IOException {
        String userId = "user123";
        String filename = "test-ontology.ttl";
        byte[] fileContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> .".getBytes();
        
        when(multipartFile.getBytes()).thenReturn(fileContent);
        when(multipartFile.getOriginalFilename()).thenReturn(filename);
        
        OntologyMetadataDto expectedDto = new OntologyMetadataDto();
        OntologyMetadataEntity savedEntity = new OntologyMetadataEntity();
        
        when(ontologyMetadataMapper.toEntity(any(OntologyMetadataDto.class))).thenReturn(savedEntity);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(savedEntity);
        when(ontologyMetadataMapper.toDto(any(OntologyMetadataEntity.class))).thenReturn(expectedDto);

        try (MockedStatic<RDFConnection> mockedRDFConnection = mockStatic(RDFConnection.class)) {
            mockedRDFConnection.when(() -> RDFConnection.connect(fusekiEndpoint)).thenReturn(rdfConnection);
            
            OntologyMetadataDto result = ontologyUploadService.uploadFromFile(multipartFile, null, Lang.TURTLE, userId);
            
            assertNotNull(result);
            verify(rdfConnection).put(argThat(graphName -> 
                graphName.contains("test-ontology") && graphName.startsWith("https://slovník.gov.cz/")
            ), any(OntModel.class));
        }
    }

    @Test
    void testUploadFromFile_EmptyProvidedName() throws IOException {
        String userId = "user123";
        String filename = "test.ttl";
        byte[] fileContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> .".getBytes();
        
        when(multipartFile.getBytes()).thenReturn(fileContent);
        when(multipartFile.getOriginalFilename()).thenReturn(filename);
        
        OntologyMetadataDto expectedDto = new OntologyMetadataDto();
        OntologyMetadataEntity savedEntity = new OntologyMetadataEntity();
        
        when(ontologyMetadataMapper.toEntity(any(OntologyMetadataDto.class))).thenReturn(savedEntity);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(savedEntity);
        when(ontologyMetadataMapper.toDto(any(OntologyMetadataEntity.class))).thenReturn(expectedDto);

        try (MockedStatic<RDFConnection> mockedRDFConnection = mockStatic(RDFConnection.class)) {
            mockedRDFConnection.when(() -> RDFConnection.connect(fusekiEndpoint)).thenReturn(rdfConnection);
            
            OntologyMetadataDto result = ontologyUploadService.uploadFromFile(multipartFile, "  ", Lang.TURTLE, userId);
            
            assertNotNull(result);
            verify(rdfConnection).put(argThat(graphName -> 
                graphName.contains("test") && graphName.startsWith("https://slovník.gov.cz/")
            ), any(OntModel.class));
        }
    }

    @Test
    void testUploadFromFile_IOExceptionHandling() throws IOException {
        String userId = "user123";
        
        when(multipartFile.getBytes()).thenThrow(new IOException("File read error"));
        
        assertThrows(IOException.class, () -> {
            ontologyUploadService.uploadFromFile(multipartFile, "test", Lang.TURTLE, userId);
        });
        
        verify(ontologyMetadataRepository, never()).save(any());
    }

    @Test
    void testUploadFromFile_NoFilename() throws IOException {
        String userId = "user123";
        byte[] fileContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> .".getBytes();
        
        when(multipartFile.getBytes()).thenReturn(fileContent);
        when(multipartFile.getOriginalFilename()).thenReturn(null);
        
        OntologyMetadataDto expectedDto = new OntologyMetadataDto();
        OntologyMetadataEntity savedEntity = new OntologyMetadataEntity();
        
        when(ontologyMetadataMapper.toEntity(any(OntologyMetadataDto.class))).thenReturn(savedEntity);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(savedEntity);
        when(ontologyMetadataMapper.toDto(any(OntologyMetadataEntity.class))).thenReturn(expectedDto);

        try (MockedStatic<RDFConnection> mockedRDFConnection = mockStatic(RDFConnection.class)) {
            mockedRDFConnection.when(() -> RDFConnection.connect(fusekiEndpoint)).thenReturn(rdfConnection);
            
            OntologyMetadataDto result = ontologyUploadService.uploadFromFile(multipartFile, null, Lang.TURTLE, userId);
            
            assertNotNull(result);
            verify(rdfConnection).put(argThat(graphName -> 
                graphName.contains("ontology") && graphName.startsWith("https://slovník.gov.cz/")
            ), any(OntModel.class));
        }
    }

 */
}