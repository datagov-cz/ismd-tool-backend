package com.dia.ismdtoolbackend.service;

import com.dia.exceptions.JsonExportException;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.EmptyDataException;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.utility.exporter.json.JsonExporter;
import com.dia.ismdtoolbackend.utility.exporter.turtle.TurtleFilterUtil;
import com.dia.ismdtoolbackend.utility.exporter.turtle.TurtleFormatterUtil;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.impl.OntologyDownloadServiceImpl;
import org.apache.jena.rdf.model.*;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.rdfconnection.RDFConnectionRemote;
import org.apache.jena.rdfconnection.RDFConnectionRemoteBuilder;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.Writer;
import java.net.http.HttpClient;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import static com.dia.constants.VocabularyConstants.SLOVNIKY_NS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OntologyDownloadServiceImplTest {

    @Mock
    private OntologyMetadataRepository ontologyMetadataRepository;

    @Mock
    private JsonExporter jsonExporter;

    @Mock
    private HttpClient fusekiHttpClient;

    @Mock
    private RDFConnection rdfConnection;

    @Mock
    private Model rawModel;

    @Mock
    private Model filteredModel;

    @Mock
    private Model ofnFormattedModel;

    @Mock
    private Property pojemProperty;

    @Mock
    private Resource resource1;

    @Mock
    private Resource resource2;

    private OntologyDownloadServiceImpl service;

    private static final String FUSEKI_ENDPOINT = "http://localhost:3030/ds";
    private static final Long ONTOLOGY_ID = 1L;
    private static final String GRAPH_NAME = "http://example.org/graph";

    @BeforeEach
    void setUp() {
        Semaphore semaphore = new Semaphore(10, true);
        service = new OntologyDownloadServiceImpl(FUSEKI_ENDPOINT, fusekiHttpClient, ontologyMetadataRepository, jsonExporter, semaphore, 30000);
    }

    private MockedStatic<RDFConnectionRemote> mockRDFConnectionRemote() {
        RDFConnectionRemoteBuilder builder = mock(RDFConnectionRemoteBuilder.class);
        when(builder.destination(anyString())).thenReturn(builder);
        when(builder.httpClient(any(HttpClient.class))).thenReturn(builder);
        when(builder.build()).thenReturn(rdfConnection);

        MockedStatic<RDFConnectionRemote> staticMock = mockStatic(RDFConnectionRemote.class);
        staticMock.when(RDFConnectionRemote::newBuilder).thenReturn(builder);
        return staticMock;
    }

    @Test
    void downloadOntology_TtlFormat_Success_WithRDFConnectionMocking() {
        // Arrange
        OntologyMetadataEntity metadata = createOntologyMetadata();
        when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.of(metadata));

        try (MockedStatic<RDFConnectionRemote> rdfConnectionMock = mockRDFConnectionRemote()) {

            // Mock fetch returning non-empty model
            when(rdfConnection.fetch(GRAPH_NAME)).thenReturn(rawModel);
            when(rawModel.isEmpty()).thenReturn(false);
            when(rawModel.size()).thenReturn(100L);

            // Mock static utility transformations
            try (MockedStatic<TurtleFilterUtil> filterUtil = mockStatic(TurtleFilterUtil.class);
                 MockedStatic<TurtleFormatterUtil> formatterUtil = mockStatic(TurtleFormatterUtil.class)) {

                filterUtil.when(() -> TurtleFilterUtil.createFilteredModel(rawModel))
                        .thenReturn(filteredModel);
                when(filteredModel.size()).thenReturn(80L);

                formatterUtil.when(() -> TurtleFormatterUtil.transformToOFNFormat(filteredModel))
                        .thenReturn(ofnFormattedModel);
                when(ofnFormattedModel.size()).thenReturn(75L);

                // Mock validation - no duplicates
                mockValidationNoDuplicates(ofnFormattedModel);

                // Mock TTL write operation
                doAnswer(invocation -> {
                    Writer writer = invocation.getArgument(0);
                    writer.write("@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n");
                    writer.write("@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n");
                    return null;
                }).when(ofnFormattedModel).write(any(Writer.class), eq("TTL"));

                // Act
                String result = service.downloadOntology(ONTOLOGY_ID, "ttl");

                // Assert
                assertNotNull(result);
                assertTrue(result.contains("@prefix rdf:"));
                assertTrue(result.contains("@prefix rdfs:"));
                verify(rdfConnection).fetch(GRAPH_NAME);
                verify(rdfConnection).close();
            }
        }
    }

    @Test
    void validateNoDuplicateTransformations_NoDuplicates_NoWarnings() {
        // Arrange
        OntologyMetadataEntity metadata = createOntologyMetadata();
        when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.of(metadata));

        try (MockedStatic<RDFConnectionRemote> rdfConnectionMock = mockRDFConnectionRemote()) {
            when(rdfConnection.fetch(GRAPH_NAME)).thenReturn(rawModel);
            when(rawModel.isEmpty()).thenReturn(false);

            try (MockedStatic<TurtleFilterUtil> filterUtil = mockStatic(TurtleFilterUtil.class);
                 MockedStatic<TurtleFormatterUtil> formatterUtil = mockStatic(TurtleFormatterUtil.class)) {

                filterUtil.when(() -> TurtleFilterUtil.createFilteredModel(rawModel))
                        .thenReturn(filteredModel);
                formatterUtil.when(() -> TurtleFormatterUtil.transformToOFNFormat(filteredModel))
                        .thenReturn(ofnFormattedModel);

                // Mock validation with unique type assertions (no duplicates)
                when(ofnFormattedModel.getProperty(SLOVNIKY_NS + "pojem")).thenReturn(pojemProperty);

                // Create mock statement iterator with one resource
                Statement stmt1 = mock(Statement.class);
                when(stmt1.getSubject()).thenReturn(resource1);
                when(resource1.getURI()).thenReturn("http://example.org/resource1");

                StmtIterator mainIterator = createMockStmtIterator(stmt1);
                when(ofnFormattedModel.listStatements(null, RDF.type, pojemProperty))
                        .thenReturn(mainIterator);

                // For each resource, return iterator with count=1 (no duplicates)
                Statement typeStmt1 = mock(Statement.class);
                StmtIterator typeIterator1 = createMockStmtIterator(typeStmt1);
                when(ofnFormattedModel.listStatements(resource1, RDF.type, pojemProperty))
                        .thenReturn(typeIterator1);

                // Mock TTL write
                doAnswer(invocation -> {
                    Writer writer = invocation.getArgument(0);
                    writer.write("@prefix test: <http://test.org/> .");
                    return null;
                }).when(ofnFormattedModel).write(any(Writer.class), eq("TTL"));

                // Act
                String result = service.downloadOntology(ONTOLOGY_ID, "ttl");

                // Assert
                assertNotNull(result);
            }
        }
    }

    @Test
    void validateNoDuplicateTransformations_WithDuplicates_LogsWarning() {
        // Arrange
        OntologyMetadataEntity metadata = createOntologyMetadata();
        when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.of(metadata));

        try (MockedStatic<RDFConnectionRemote> rdfConnectionMock = mockRDFConnectionRemote()) {
            when(rdfConnection.fetch(GRAPH_NAME)).thenReturn(rawModel);
            when(rawModel.isEmpty()).thenReturn(false);

            try (MockedStatic<TurtleFilterUtil> filterUtil = mockStatic(TurtleFilterUtil.class);
                 MockedStatic<TurtleFormatterUtil> formatterUtil = mockStatic(TurtleFormatterUtil.class)) {

                filterUtil.when(() -> TurtleFilterUtil.createFilteredModel(rawModel))
                        .thenReturn(filteredModel);
                formatterUtil.when(() -> TurtleFormatterUtil.transformToOFNFormat(filteredModel))
                        .thenReturn(ofnFormattedModel);

                // Mock validation with duplicate type assertions
                when(ofnFormattedModel.getProperty(SLOVNIKY_NS + "pojem")).thenReturn(pojemProperty);

                // Create mock statement iterator with one resource that has duplicates
                Statement stmt1 = mock(Statement.class);
                when(stmt1.getSubject()).thenReturn(resource1);
                when(resource1.getURI()).thenReturn("http://example.org/duplicateResource");

                StmtIterator mainIterator = createMockStmtIterator(stmt1);
                when(ofnFormattedModel.listStatements(null, RDF.type, pojemProperty))
                        .thenReturn(mainIterator);

                // For this resource, return iterator with count=3 (duplicates!)
                Statement typeStmt1 = mock(Statement.class);
                Statement typeStmt2 = mock(Statement.class);
                Statement typeStmt3 = mock(Statement.class);
                StmtIterator typeIterator1 = createMockStmtIterator(typeStmt1, typeStmt2, typeStmt3);
                when(ofnFormattedModel.listStatements(resource1, RDF.type, pojemProperty))
                        .thenReturn(typeIterator1);

                // Mock TTL write
                doAnswer(invocation -> {
                    Writer writer = invocation.getArgument(0);
                    writer.write("@prefix test: <http://test.org/> .");
                    return null;
                }).when(ofnFormattedModel).write(any(Writer.class), eq("TTL"));

                // Act
                String result = service.downloadOntology(ONTOLOGY_ID, "ttl");

                // Assert
                assertNotNull(result);
            }
        }
    }

    @Test
    void exportToOFNJson_ExporterThrowsException_WrapsInJsonExportException() {
        // Arrange
        OntologyMetadataEntity metadata = createOntologyMetadata();
        when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.of(metadata));

        try (MockedStatic<RDFConnectionRemote> rdfConnectionMock = mockRDFConnectionRemote()) {
            when(rdfConnection.fetch(GRAPH_NAME)).thenReturn(rawModel);
            when(rawModel.isEmpty()).thenReturn(false);

            try (MockedStatic<TurtleFilterUtil> filterUtil = mockStatic(TurtleFilterUtil.class);
                 MockedStatic<TurtleFormatterUtil> formatterUtil = mockStatic(TurtleFormatterUtil.class)) {

                filterUtil.when(() -> TurtleFilterUtil.createFilteredModel(rawModel))
                        .thenReturn(filteredModel);
                formatterUtil.when(() -> TurtleFormatterUtil.transformToOFNFormat(filteredModel))
                        .thenReturn(ofnFormattedModel);

                mockValidationNoDuplicates(ofnFormattedModel);

                // Mock JsonExporter to throw exception
                RuntimeException originalException = new RuntimeException("JSON serialization failed");
                when(jsonExporter.exportToJson(ofnFormattedModel)).thenThrow(originalException);

                // Act & Assert
                JsonExportException exception = assertThrows(JsonExportException.class, () -> {
                    service.downloadOntology(ONTOLOGY_ID, "json-ld");
                });

                assertTrue(exception.getMessage().contains("Chyba při exportu do JSON formátu"));
                assertEquals(originalException, exception.getCause());
            }
        }
    }

    @Test
    void validateNoDuplicateTransformations_EmptyModel_NoErrors() {
        // Arrange
        OntologyMetadataEntity metadata = createOntologyMetadata();
        when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.of(metadata));

        try (MockedStatic<RDFConnectionRemote> rdfConnectionMock = mockRDFConnectionRemote()) {
            when(rdfConnection.fetch(GRAPH_NAME)).thenReturn(rawModel);
            when(rawModel.isEmpty()).thenReturn(false);

            try (MockedStatic<TurtleFilterUtil> filterUtil = mockStatic(TurtleFilterUtil.class);
                 MockedStatic<TurtleFormatterUtil> formatterUtil = mockStatic(TurtleFormatterUtil.class)) {

                filterUtil.when(() -> TurtleFilterUtil.createFilteredModel(rawModel))
                        .thenReturn(filteredModel);
                formatterUtil.when(() -> TurtleFormatterUtil.transformToOFNFormat(filteredModel))
                        .thenReturn(ofnFormattedModel);

                // Mock validation with empty iterator (no statements)
                when(ofnFormattedModel.getProperty(SLOVNIKY_NS + "pojem")).thenReturn(pojemProperty);

                StmtIterator emptyIterator = createEmptyStmtIterator();
                when(ofnFormattedModel.listStatements(null, RDF.type, pojemProperty))
                        .thenReturn(emptyIterator);

                // Mock TTL write
                doAnswer(invocation -> {
                    Writer writer = invocation.getArgument(0);
                    writer.write("@prefix test: <http://test.org/> .");
                    return null;
                }).when(ofnFormattedModel).write(any(Writer.class), eq("TTL"));

                // Act
                String result = service.downloadOntology(ONTOLOGY_ID, "ttl");

                // Assert
                assertNotNull(result);
            }
        }
    }

    // ========== HELPER METHODS ==========

    private StmtIterator createMockStmtIterator(Statement... statements) {
        StmtIterator iterator = mock(StmtIterator.class);

        if (statements.length == 0) {
            when(iterator.hasNext()).thenReturn(false);
            return iterator;
        }

        Boolean[] hasNextSequence = new Boolean[statements.length + 1];
        for (int i = 0; i < statements.length; i++) {
            hasNextSequence[i] = true;
        }
        hasNextSequence[statements.length] = false;

        when(iterator.hasNext()).thenReturn(true, hasNextSequence);

        if (statements.length == 1) {
            when(iterator.next()).thenReturn(statements[0]);
        } else {
            Statement[] remainingStatements = new Statement[statements.length - 1];
            System.arraycopy(statements, 1, remainingStatements, 0, statements.length - 1);
            when(iterator.next()).thenReturn(statements[0], remainingStatements);
        }

        return iterator;
    }

    private StmtIterator createEmptyStmtIterator() {
        StmtIterator iterator = mock(StmtIterator.class);
        when(iterator.hasNext()).thenReturn(false);
        return iterator;
    }

    private void mockValidationNoDuplicates(Model model) {
        when(model.getProperty(SLOVNIKY_NS + "pojem")).thenReturn(pojemProperty);
        StmtIterator emptyIterator = createEmptyStmtIterator();
        when(model.listStatements(null, RDF.type, pojemProperty)).thenReturn(emptyIterator);
    }

    private OntologyMetadataEntity createOntologyMetadata() {
        OntologyMetadataEntity entity = new OntologyMetadataEntity();
        entity.setId(ONTOLOGY_ID);
        entity.setGraphName(GRAPH_NAME);
        return entity;
    }

    // ========== PROSÍM DOKONČI TYTO TESTY ==========

    @Test
    void downloadOntology_OntologyNotFound_ThrowsOntologyNotFoundException() {
        // Arrange - repozitář vrací prázdný výsledek
        when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.empty());

        // Act & Assert
        OntologyNotFoundException exception = assertThrows(OntologyNotFoundException.class,
                () -> service.downloadOntology(ONTOLOGY_ID, "ttl"));

        // Assert - ověření zprávy výjimky
        assertEquals("Metadata slovníku nebyla nalezena", exception.getMessage());
    }

    @Test
    void downloadOntology_EmptyModel_ThrowsOntologyException() {
        // Arrange
        OntologyMetadataEntity metadata = createOntologyMetadata();
        when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.of(metadata));

        try (MockedStatic<RDFConnectionRemote> rdfConnectionMock = mockRDFConnectionRemote()) {
            when(rdfConnection.fetch(GRAPH_NAME)).thenReturn(rawModel);
            when(rawModel.isEmpty()).thenReturn(true);

            // Act
            EmptyDataException ex = assertThrows(EmptyDataException.class,
                    () -> service.downloadOntology(ONTOLOGY_ID, "ttl"));

            // Assert
            assertTrue(ex.getMessage().contains("Slovník je prázdný, nebo nebyl nalezen."));
        }
    }

    @Test
    void downloadOntology_UnsupportedFormat_ThrowsIllegalArgumentException() {
        // Arrange
        OntologyMetadataEntity metadata = createOntologyMetadata();
        when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.of(metadata));

        try (MockedStatic<RDFConnectionRemote> rdfConnStatic = mockRDFConnectionRemote()) {
            when(rdfConnection.fetch(GRAPH_NAME)).thenReturn(rawModel);
            when(rawModel.isEmpty()).thenReturn(false);

            try (MockedStatic<TurtleFilterUtil> filterUtil = mockStatic(TurtleFilterUtil.class);
                 MockedStatic<TurtleFormatterUtil> fmtUtil = mockStatic(TurtleFormatterUtil.class)) {

                filterUtil.when(() -> TurtleFilterUtil.createFilteredModel(rawModel)).thenReturn(filteredModel);
                fmtUtil.when(() -> TurtleFormatterUtil.transformToOFNFormat(filteredModel)).thenReturn(ofnFormattedModel);

                mockValidationNoDuplicates(ofnFormattedModel);

                // Act
                IllegalArgumentException ex = assertThrows(
                        IllegalArgumentException.class, () -> service.downloadOntology(ONTOLOGY_ID, "xml")
                );

                // Assert
                assertTrue(ex.getMessage().contains("Nepodporovaný formát"),
                        "Zpráva výjimky musí obsahovat 'Nepodporovaný formát'");

                // Verify
                verifyNoInteractions(jsonExporter);
                verify(rdfConnection).fetch(GRAPH_NAME);
                verify(rdfConnection).close();
                verifyNoMoreInteractions(rdfConnection);
            }
        }
    }

    @Test
    void downloadOntology_JsonLdFormat_Success() {
        // Arrange
        OntologyMetadataEntity metadata = createOntologyMetadata();
        when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.of(metadata));

        try (MockedStatic<RDFConnectionRemote> rdfConnStatic = mockRDFConnectionRemote()) {
            when(rdfConnection.fetch(GRAPH_NAME)).thenReturn(rawModel);
            when(rawModel.isEmpty()).thenReturn(false);

            try (MockedStatic<TurtleFilterUtil> filterUtil = mockStatic(TurtleFilterUtil.class);
                 MockedStatic<TurtleFormatterUtil> fmtUtil = mockStatic(TurtleFormatterUtil.class)) {

                filterUtil.when(() -> TurtleFilterUtil.createFilteredModel(rawModel)).thenReturn(filteredModel);
                fmtUtil.when(() -> TurtleFormatterUtil.transformToOFNFormat(filteredModel)).thenReturn(ofnFormattedModel);

                mockValidationNoDuplicates(ofnFormattedModel);

                String expectedJson = "{\"@graph\":[]}";
                when(jsonExporter.exportToJson(ofnFormattedModel)).thenReturn(expectedJson);

                // Act
                String result = service.downloadOntology(ONTOLOGY_ID, "json-ld");

                // Assert
                assertEquals(expectedJson, result);

                // Verify
                verify(ofnFormattedModel, never()).write(any(Writer.class), anyString());
                verify(rdfConnection).fetch(GRAPH_NAME);
                verify(rdfConnection).close();
                verify(jsonExporter).exportToJson(ofnFormattedModel);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"JSON-LD", "json-ld", "Json-Ld"})
    void downloadOntology_CaseInsensitiveFormat_JsonLd(String formatVariant) {
        // Arrange
        OntologyMetadataEntity metadata = createOntologyMetadata();
        when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.of(metadata));

        try (MockedStatic<RDFConnectionRemote> rdfConnStatic = mockRDFConnectionRemote()) {
            when(rdfConnection.fetch(GRAPH_NAME)).thenReturn(rawModel);
            when(rawModel.isEmpty()).thenReturn(false);

            try (MockedStatic<TurtleFilterUtil> filterUtil = mockStatic(TurtleFilterUtil.class);
                 MockedStatic<TurtleFormatterUtil> fmtUtil = mockStatic(TurtleFormatterUtil.class)) {

                filterUtil.when(() -> TurtleFilterUtil.createFilteredModel(rawModel)).thenReturn(filteredModel);
                fmtUtil.when(() -> TurtleFormatterUtil.transformToOFNFormat(filteredModel)).thenReturn(ofnFormattedModel);

                mockValidationNoDuplicates(ofnFormattedModel);

                String expectedJson = "{\"ok\":true}";
                when(jsonExporter.exportToJson(ofnFormattedModel)).thenReturn(expectedJson);

                // Act
                String result = service.downloadOntology(ONTOLOGY_ID, formatVariant);

                // Assert
                assertEquals(expectedJson, result);

                // Verify
                verify(ofnFormattedModel, never()).write(any(Writer.class), anyString());
                verify(rdfConnection).fetch(GRAPH_NAME);
                verify(rdfConnection).close();
                verify(jsonExporter).exportToJson(ofnFormattedModel);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TTL", "ttl", "TtL"})
    void downloadOntology_CaseInsensitiveFormat_Ttl(String formatVariant) {
        // Arrange
        OntologyMetadataEntity metadata = createOntologyMetadata();
        when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.of(metadata));

        try (MockedStatic<RDFConnectionRemote> rdfConnStatic = mockRDFConnectionRemote()) {
            when(rdfConnection.fetch(GRAPH_NAME)).thenReturn(rawModel);
            when(rawModel.isEmpty()).thenReturn(false);

            try (MockedStatic<TurtleFilterUtil> filterUtil = mockStatic(TurtleFilterUtil.class);
                 MockedStatic<TurtleFormatterUtil> fmtUtil = mockStatic(TurtleFormatterUtil.class)) {

                filterUtil.when(() -> TurtleFilterUtil.createFilteredModel(rawModel)).thenReturn(filteredModel);
                fmtUtil.when(() -> TurtleFormatterUtil.transformToOFNFormat(filteredModel)).thenReturn(ofnFormattedModel);

                mockValidationNoDuplicates(ofnFormattedModel);

                doAnswer(invocation -> {
                    Writer writer = invocation.getArgument(0);
                    writer.write("@prefix ex: <http://example.org/> .");
                    return null;
                }).when(ofnFormattedModel).write(any(Writer.class), eq("TTL"));

                // Act
                String result = service.downloadOntology(ONTOLOGY_ID, formatVariant);

                // Assert
                assertNotNull(result);
                assertTrue(result.contains("@prefix ex:"), "Returned TTL should contain test prefix");

                // Verify
                verify(ofnFormattedModel).write(any(Writer.class), eq("TTL"));
                verify(jsonExporter, never()).exportToJson(any(Model.class));
                verify(rdfConnection).fetch(GRAPH_NAME);
                verify(rdfConnection).close();
            }
        }
    }

    @Test
    void downloadOntology_NullFormat_ThrowsException() {
        // Arrange
        OntologyMetadataEntity metadata = createOntologyMetadata();
        when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.of(metadata));

        try (MockedStatic<RDFConnectionRemote> rdfConnStatic = mockRDFConnectionRemote()) {
            when(rdfConnection.fetch(GRAPH_NAME)).thenReturn(rawModel);
            when(rawModel.isEmpty()).thenReturn(false);

            try (MockedStatic<TurtleFilterUtil> filterUtil = mockStatic(TurtleFilterUtil.class);
                 MockedStatic<TurtleFormatterUtil> fmtUtil = mockStatic(TurtleFormatterUtil.class)) {

                filterUtil.when(() -> TurtleFilterUtil.createFilteredModel(rawModel)).thenReturn(filteredModel);
                fmtUtil.when(() -> TurtleFormatterUtil.transformToOFNFormat(filteredModel)).thenReturn(ofnFormattedModel);

                mockValidationNoDuplicates(ofnFormattedModel);

                // Act
                IllegalArgumentException ex = assertThrows(
                        IllegalArgumentException.class,
                        () -> service.downloadOntology(ONTOLOGY_ID, null)
                );

                // Assert
                assertNotNull(ex.getMessage());

                // Verify
                verify(rdfConnection).fetch(GRAPH_NAME);
                verify(rdfConnection).close();
                verifyNoInteractions(jsonExporter);
            }
        }
    }

    @Test
    void applyAllOFNTransformations_StatementCountReduction() {
        // Arrange
        OntologyMetadataEntity metadata = createOntologyMetadata();
        when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.of(metadata));

        try (MockedStatic<RDFConnectionRemote> rdfConnStatic = mockRDFConnectionRemote()) {
            when(rdfConnection.fetch(GRAPH_NAME)).thenReturn(rawModel);
            when(rawModel.isEmpty()).thenReturn(false);
            when(rawModel.size()).thenReturn(100L);

            try (MockedStatic<TurtleFilterUtil> filterUtil = mockStatic(TurtleFilterUtil.class);
                MockedStatic<TurtleFormatterUtil> ftmUtil = mockStatic(TurtleFormatterUtil.class)) {

                filterUtil.when(() -> TurtleFilterUtil.createFilteredModel(rawModel)).thenReturn(filteredModel);
                when(filteredModel.size()).thenReturn(80L);

                ftmUtil.when(() -> TurtleFormatterUtil.transformToOFNFormat(filteredModel)).thenReturn(ofnFormattedModel);
                when(ofnFormattedModel.size()).thenReturn(75L);

                mockValidationNoDuplicates(ofnFormattedModel);

                doAnswer(invocation -> {
                    Writer w = invocation.getArgument(0);
                    w.write("@prefix ex: <http://example.org/> .\n# size=75");
                    return null;
                }).when(ofnFormattedModel).write(any(Writer.class), eq("TTL"));

                // Act
                String ttl = service.downloadOntology(ONTOLOGY_ID, "ttl");

                // Assert
                assertNotNull(ttl);
                assertTrue(ttl.contains("@prefix ex:"), "TTL output should come from the formatted model");

                // Verify
                filterUtil.verify(() -> TurtleFilterUtil.createFilteredModel(rawModel), times(1));
                ftmUtil.verify(() -> TurtleFormatterUtil.transformToOFNFormat(filteredModel), times(1));

                verify(ofnFormattedModel, times(1)).write(any(Writer.class), eq("TTL"));
                verify(filteredModel, never()).write(any(Writer.class), anyString());
                verify(rawModel, never()).write(any(Writer.class), anyString());

                verify(jsonExporter, never()).exportToJson(any(Model.class));
                verify(rdfConnection).fetch(GRAPH_NAME);
                verify(rdfConnection).close();
            }
        }
    }

    @Test
    void exportToOFNJson_Success_ReturnsJsonString() {
        // Arrange
        OntologyMetadataEntity metadata = createOntologyMetadata();
        when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.of(metadata));

        try (MockedStatic<RDFConnectionRemote> rdfConnStatic = mockRDFConnectionRemote()) {
            when(rdfConnection.fetch(GRAPH_NAME)).thenReturn(rawModel);
            when(rawModel.isEmpty()).thenReturn(false);

            try (MockedStatic<TurtleFilterUtil> filterUtil = mockStatic(TurtleFilterUtil.class);
                 MockedStatic<TurtleFormatterUtil> fmtUtil = mockStatic(TurtleFormatterUtil.class)) {

                filterUtil.when(() -> TurtleFilterUtil.createFilteredModel(rawModel)).thenReturn(filteredModel);
                fmtUtil.when(() -> TurtleFormatterUtil.transformToOFNFormat(filteredModel)).thenReturn(ofnFormattedModel);

                mockValidationNoDuplicates(ofnFormattedModel);

                String expected = "{\\\"@graph\\\":[{\\\"@id\\\":\\\"ex:one\\\"}]}";
                when(jsonExporter.exportToJson(ofnFormattedModel)).thenReturn(expected);

                // Act
                String result = service.downloadOntology(ONTOLOGY_ID, "json-ld");

                // Assert
                assertEquals(expected, result);

                // Verify
                verify(jsonExporter, times(1)).exportToJson(ofnFormattedModel);
                verify(ofnFormattedModel, never()).write(any(Writer.class), anyString());
                verify(rdfConnection).fetch(GRAPH_NAME);
                verify(rdfConnection).close();
            }
        }
    }

    @Test
    void downloadOntology_RdfConnectionException_PropagatesException() {
        // Arrange
        OntologyMetadataEntity metadata = createOntologyMetadata();
        when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.of(metadata));

        RuntimeException runEx = new RuntimeException("Connection failed");

        RDFConnectionRemoteBuilder builder = mock(RDFConnectionRemoteBuilder.class);
        when(builder.destination(anyString())).thenReturn(builder);
        when(builder.httpClient(any(HttpClient.class))).thenReturn(builder);
        when(builder.build()).thenThrow(runEx);

        try (MockedStatic<RDFConnectionRemote> rdfConnStatic = mockStatic(RDFConnectionRemote.class)) {
            rdfConnStatic.when(RDFConnectionRemote::newBuilder).thenReturn(builder);

            // Act
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.downloadOntology(ONTOLOGY_ID, "ttl"));

            // Assert
            assertSame(runEx, ex);

            // Verify
            verifyNoInteractions(rdfConnection);
            verifyNoInteractions(jsonExporter);
        }
    }
}
