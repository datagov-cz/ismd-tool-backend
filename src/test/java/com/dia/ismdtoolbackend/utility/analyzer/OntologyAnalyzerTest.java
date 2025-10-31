package com.dia.ismdtoolbackend.utility.analyzer;

import com.dia.ismdtoolbackend.exception.OntologyAnalysisException;
import org.apache.jena.ontology.OntClass;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntProperty;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static com.dia.constants.VocabularyConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OntologyAnalyzerTest {

    @InjectMocks
    private OntologyAnalyzer ontologyAnalyzer;

    @Mock
    private OntModel ontModel;

    @Mock
    private OntClass ontClass;

    @Mock
    private OntProperty ontProperty;

    @Mock
    private ExtendedIterator<OntClass> classIterator;

    @Mock
    private ExtendedIterator<OntProperty> propertyIterator;

    @Mock
    private ExtendedIterator<OntClass> superClassIterator;

    @Mock
    private StmtIterator stmtIterator;

    @Mock
    private Statement statement;

    @Mock
    private Resource resource;

    @BeforeEach
    void setUp() {
        // Reset all mocks before each test
    }

    // ========== Basic Analysis Tests ==========

    @Test
    void testAnalyzeUploadedOntology_EmptyOntology() throws OntologyAnalysisException {
        // Arrange
        when(ontModel.listClasses()).thenReturn(classIterator);
        when(classIterator.hasNext()).thenReturn(false);
        when(ontModel.listOntProperties()).thenReturn(propertyIterator);
        when(propertyIterator.hasNext()).thenReturn(false);

        // Act
        AnalysisResult result = ontologyAnalyzer.analyzeUploadedOntology(ontModel);

        // Assert
        assertNotNull(result);
        assertTrue(result.requiredBaseClasses().contains(POJEM));
        assertEquals(1, result.requiredBaseClasses().size());
        assertTrue(result.requiredProperties().isEmpty());
    }

    @Test
    void testAnalyzeUploadedOntology_WithOFNClass() throws OntologyAnalysisException {
        // Arrange
        String classURI = "https://slovník.gov.cz/generický/test/pojem/test-třída";

        when(ontModel.listClasses()).thenReturn(classIterator);
        when(classIterator.hasNext()).thenReturn(true, false);
        when(classIterator.next()).thenReturn(ontClass);

        when(ontClass.isURIResource()).thenReturn(true);
        when(ontClass.getURI()).thenReturn(classURI);
        when(ontClass.listSuperClasses(true)).thenReturn(superClassIterator);
        when(superClassIterator.hasNext()).thenReturn(false);
        when(ontClass.listProperties(RDF.type)).thenReturn(stmtIterator);
        when(stmtIterator.hasNext()).thenReturn(false);

        when(ontModel.listOntProperties()).thenReturn(propertyIterator);
        when(propertyIterator.hasNext()).thenReturn(false);

        // Act
        AnalysisResult result = ontologyAnalyzer.analyzeUploadedOntology(ontModel);

        // Assert
        assertNotNull(result);
        assertTrue(result.requiredBaseClasses().contains(POJEM));
    }

    @Test
    void testAnalyzeUploadedOntology_WithTSPType() throws OntologyAnalysisException {
        // Arrange
        String classURI = "https://slovník.gov.cz/generický/test/pojem/test-class";
        String tspTypeURI = "https://slovník.gov.cz/veřejný-sektor/pojem/typ-subjektu-práva";

        when(ontModel.listClasses()).thenReturn(classIterator);
        when(classIterator.hasNext()).thenReturn(true, false);
        when(classIterator.next()).thenReturn(ontClass);

        when(ontClass.isURIResource()).thenReturn(true);
        when(ontClass.getURI()).thenReturn(classURI);
        when(ontClass.listSuperClasses(true)).thenReturn(superClassIterator);
        when(superClassIterator.hasNext()).thenReturn(false);
        when(ontClass.listProperties(RDF.type)).thenReturn(stmtIterator);
        when(stmtIterator.hasNext()).thenReturn(true, false);
        when(stmtIterator.next()).thenReturn(statement);
        when(statement.getObject()).thenReturn(resource);
        when(resource.isURIResource()).thenReturn(true);
        when(resource.asResource()).thenReturn(resource);
        when(resource.getURI()).thenReturn(tspTypeURI);

        when(ontModel.listOntProperties()).thenReturn(propertyIterator);
        when(propertyIterator.hasNext()).thenReturn(false);

        // Act
        AnalysisResult result = ontologyAnalyzer.analyzeUploadedOntology(ontModel);

        // Assert
        assertNotNull(result);
        assertTrue(result.requiredBaseClasses().contains(TSP));
        assertTrue(result.requiredBaseClasses().contains(TRIDA));
        assertTrue(result.requiredBaseClasses().contains(POJEM));
        assertEquals(3, result.requiredBaseClasses().size());
    }

    @Test
    void testAnalyzeUploadedOntology_WithTOPType() throws OntologyAnalysisException {
        // Arrange
        String classURI = "https://slovník.gov.cz/generický/test/pojem/test-class";
        String topTypeURI = "https://slovník.gov.cz/veřejný-sektor/pojem/typ-objektu-práva";

        when(ontModel.listClasses()).thenReturn(classIterator);
        when(classIterator.hasNext()).thenReturn(true, false);
        when(classIterator.next()).thenReturn(ontClass);

        when(ontClass.isURIResource()).thenReturn(true);
        when(ontClass.getURI()).thenReturn(classURI);
        when(ontClass.listSuperClasses(true)).thenReturn(superClassIterator);
        when(superClassIterator.hasNext()).thenReturn(false);
        when(ontClass.listProperties(RDF.type)).thenReturn(stmtIterator);
        when(stmtIterator.hasNext()).thenReturn(true, false);
        when(stmtIterator.next()).thenReturn(statement);
        when(statement.getObject()).thenReturn(resource);
        when(resource.isURIResource()).thenReturn(true);
        when(resource.asResource()).thenReturn(resource);
        when(resource.getURI()).thenReturn(topTypeURI);

        when(ontModel.listOntProperties()).thenReturn(propertyIterator);
        when(propertyIterator.hasNext()).thenReturn(false);

        // Act
        AnalysisResult result = ontologyAnalyzer.analyzeUploadedOntology(ontModel);

        // Assert
        assertNotNull(result);
        assertTrue(result.requiredBaseClasses().contains(TOP));
        assertTrue(result.requiredBaseClasses().contains(TRIDA));
        assertTrue(result.requiredBaseClasses().contains(POJEM));
        assertEquals(3, result.requiredBaseClasses().size());
    }

    @Test
    void testAnalyzeUploadedOntology_WithUdajType() throws OntologyAnalysisException {
        // Arrange
        String classURI = "https://slovník.gov.cz/generický/test/pojem/test-class";
        String udajTypeURI = "https://slovník.gov.cz/generický/test/pojem/údaj";

        when(ontModel.listClasses()).thenReturn(classIterator);
        when(classIterator.hasNext()).thenReturn(true, false);
        when(classIterator.next()).thenReturn(ontClass);

        when(ontClass.isURIResource()).thenReturn(true);
        when(ontClass.getURI()).thenReturn(classURI);
        when(ontClass.listSuperClasses(true)).thenReturn(superClassIterator);
        when(superClassIterator.hasNext()).thenReturn(false);
        when(ontClass.listProperties(RDF.type)).thenReturn(stmtIterator);
        when(stmtIterator.hasNext()).thenReturn(true, false);
        when(stmtIterator.next()).thenReturn(statement);
        when(statement.getObject()).thenReturn(resource);
        when(resource.isURIResource()).thenReturn(true);
        when(resource.asResource()).thenReturn(resource);
        when(resource.getURI()).thenReturn(udajTypeURI);

        when(ontModel.listOntProperties()).thenReturn(propertyIterator);
        when(propertyIterator.hasNext()).thenReturn(false);

        // Act
        AnalysisResult result = ontologyAnalyzer.analyzeUploadedOntology(ontModel);

        // Assert
        assertNotNull(result);
        assertTrue(result.requiredBaseClasses().contains(UDAJ));
        assertTrue(result.requiredBaseClasses().contains(POJEM));
        assertEquals(2, result.requiredBaseClasses().size());
    }

    // ========== Property Detection Tests ==========

    @Test
    void testAnalyzeUploadedOntology_WithOFNProperty() throws OntologyAnalysisException {
        // Arrange
        String propertyURI = "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/název";

        when(ontModel.listClasses()).thenReturn(classIterator);
        when(classIterator.hasNext()).thenReturn(false);

        when(ontModel.listOntProperties()).thenReturn(propertyIterator);
        when(propertyIterator.hasNext()).thenReturn(true, false);
        when(propertyIterator.next()).thenReturn(ontProperty);
        when(ontProperty.isURIResource()).thenReturn(true);
        when(ontProperty.getURI()).thenReturn(propertyURI);

        // Act
        AnalysisResult result = ontologyAnalyzer.analyzeUploadedOntology(ontModel);

        // Assert
        assertNotNull(result);
        assertTrue(result.requiredProperties().contains("název"));
    }

    @Test
    void testAnalyzeUploadedOntology_WithMultipleOFNProperties() throws OntologyAnalysisException {
        // Arrange
        String property1URI = "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/název";
        String property2URI = "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/popis";

        OntProperty ontProperty2 = mock(OntProperty.class);

        when(ontModel.listClasses()).thenReturn(classIterator);
        when(classIterator.hasNext()).thenReturn(false);

        when(ontModel.listOntProperties()).thenReturn(propertyIterator);
        when(propertyIterator.hasNext()).thenReturn(true, true, false);
        when(propertyIterator.next()).thenReturn(ontProperty, ontProperty2);

        when(ontProperty.isURIResource()).thenReturn(true);
        when(ontProperty.getURI()).thenReturn(property1URI);
        when(ontProperty2.isURIResource()).thenReturn(true);
        when(ontProperty2.getURI()).thenReturn(property2URI);

        // Act
        AnalysisResult result = ontologyAnalyzer.analyzeUploadedOntology(ontModel);

        // Assert
        assertNotNull(result);
        assertTrue(result.requiredProperties().contains("název"));
        assertTrue(result.requiredProperties().contains("popis"));
        assertEquals(2, result.requiredProperties().size());
    }

    // ========== Non-OFN Classes Tests ==========

    @Test
    void testAnalyzeUploadedOntology_WithNonOFNClass() throws OntologyAnalysisException {
        // Arrange
        String classURI = "http://example.org/test/SomeClass";

        when(ontModel.listClasses()).thenReturn(classIterator);
        when(classIterator.hasNext()).thenReturn(true, false);
        when(classIterator.next()).thenReturn(ontClass);

        when(ontClass.isURIResource()).thenReturn(true);
        when(ontClass.getURI()).thenReturn(classURI);
        when(ontClass.listSuperClasses(true)).thenReturn(superClassIterator);
        when(superClassIterator.hasNext()).thenReturn(false);
        when(ontClass.listProperties(RDF.type)).thenReturn(stmtIterator);
        when(stmtIterator.hasNext()).thenReturn(false);

        when(ontModel.listOntProperties()).thenReturn(propertyIterator);
        when(propertyIterator.hasNext()).thenReturn(false);

        // Act
        AnalysisResult result = ontologyAnalyzer.analyzeUploadedOntology(ontModel);

        // Assert
        assertNotNull(result);
        assertTrue(result.requiredBaseClasses().contains(POJEM));
        assertEquals(1, result.requiredBaseClasses().size());
    }

    @Test
    void testAnalyzeUploadedOntology_WithBlankNode() throws OntologyAnalysisException {
        // Arrange
        when(ontModel.listClasses()).thenReturn(classIterator);
        when(classIterator.hasNext()).thenReturn(true, false);
        when(classIterator.next()).thenReturn(ontClass);

        when(ontClass.isURIResource()).thenReturn(false);

        when(ontModel.listOntProperties()).thenReturn(propertyIterator);
        when(propertyIterator.hasNext()).thenReturn(false);

        // Act
        AnalysisResult result = ontologyAnalyzer.analyzeUploadedOntology(ontModel);

        // Assert
        assertNotNull(result);
        assertTrue(result.requiredBaseClasses().contains(POJEM));
        verify(ontClass, never()).getURI();
    }

    // ========== Exception Handling Tests ==========

    @Test
    void testAnalyzeUploadedOntology_ThrowsOntologyAnalysisException() {
        // Arrange
        when(ontModel.listClasses()).thenThrow(new RuntimeException("Model error"));

        // Act & Assert
        assertThrows(OntologyAnalysisException.class, () ->
            ontologyAnalyzer.analyzeUploadedOntology(ontModel)
        );
    }

    @Test
    void testAnalyzeUploadedOntology_NullModel() {
        // Act & Assert
        assertThrows(Exception.class, () ->
            ontologyAnalyzer.analyzeUploadedOntology(null)
        );
    }

    // ========== Superclass Analysis Tests ==========

    @Test
    void testAnalyzeUploadedOntology_WithOFNSuperclass() throws OntologyAnalysisException {
        // Arrange
        String classURI = "http://example.org/test/MyClass";
        String superClassURI = "https://slovník.gov.cz/generický/test/pojem/třída";

        OntClass superClass = mock(OntClass.class);
        StmtIterator emptyStmtIterator = mock(StmtIterator.class);

        when(ontModel.listClasses()).thenReturn(classIterator);
        when(classIterator.hasNext()).thenReturn(true, false);
        when(classIterator.next()).thenReturn(ontClass);

        when(ontClass.isURIResource()).thenReturn(true);
        when(ontClass.getURI()).thenReturn(classURI);
        when(ontClass.listSuperClasses(true)).thenReturn(superClassIterator);
        when(superClassIterator.hasNext()).thenReturn(true, false);
        when(superClassIterator.next()).thenReturn(superClass);
        when(ontClass.listProperties(RDF.type)).thenReturn(stmtIterator);
        when(stmtIterator.hasNext()).thenReturn(false);

        when(superClass.isURIResource()).thenReturn(true);
        when(superClass.getURI()).thenReturn(superClassURI);
        when(superClass.listProperties(RDF.type)).thenReturn(emptyStmtIterator);
        when(emptyStmtIterator.hasNext()).thenReturn(false);

        when(ontModel.listOntProperties()).thenReturn(propertyIterator);
        when(propertyIterator.hasNext()).thenReturn(false);

        // Act
        AnalysisResult result = ontologyAnalyzer.analyzeUploadedOntology(ontModel);

        // Assert
        assertNotNull(result);
        assertTrue(result.requiredBaseClasses().contains(POJEM));
    }
}
