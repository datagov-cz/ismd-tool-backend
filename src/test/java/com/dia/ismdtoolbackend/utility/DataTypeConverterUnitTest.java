package com.dia.ismdtoolbackend.utility;

import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.XSD;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DataTypeConverterUnitTest {

    private Model model;
    private Resource subject;
    private Property property;

    @BeforeEach
    void setUp() {
        model = ModelFactory.createDefaultModel();
        subject = model.createResource("https://example.com/s");
        property = model.createProperty("https://example.com/p");
    }

    @Test
    void addLangLiteral_tagsTheValueWithTheLanguage() {
        DataTypeConverter.addLangLiteral(subject, property, "Adresa", "cs");

        Literal literal = subject.getProperty(property).getLiteral();
        assertEquals("Adresa", literal.getLexicalForm());
        assertEquals("cs", literal.getLanguage());
        assertEquals(RDF.langString.getURI(), literal.getDatatypeURI());
    }

    @Test
    void addLangLiteral_doesNotGuessATypeFromTheValue() {
        DataTypeConverter.addLangLiteral(subject, property, "true", "cs");
        DataTypeConverter.addLangLiteral(subject, property, "2024-01-01", "en");

        assertTrue(subject.listProperties(property).toList().stream()
                .allMatch(s -> RDF.langString.getURI().equals(s.getLiteral().getDatatypeURI())));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void addLangLiteral_writesAPlainStringWithoutALanguage(String lang) {
        DataTypeConverter.addLangLiteral(subject, property, "Adresa", lang);

        Literal literal = subject.getProperty(property).getLiteral();
        assertEquals("", literal.getLanguage());
        assertEquals(XSD.xstring.getURI(), literal.getDatatypeURI());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void addLangLiteral_skipsBlankValues(String value) {
        DataTypeConverter.addLangLiteral(subject, property, value, "cs");

        assertFalse(subject.hasProperty(property));
    }

    @Test
    void isUri_acceptsAbsoluteIris() {
        assertTrue(DataTypeConverter.isUri("https://example.com"));
        assertTrue(DataTypeConverter.isUri("http://test.org/path"));
        assertTrue(DataTypeConverter.isUri("ftp://files.example.com"));
    }

    @Test
    void isUri_acceptsNonAsciiIris() {
        // java.net.URI rejects Czech diacritics; these IRIs must be recognised as-is.
        assertTrue(DataTypeConverter.isUri("https://slovník.gov.cz/a124-datový-slovník-iskn/pojem/právní-vztah"));
        assertTrue(DataTypeConverter.isUri("https://slovník.gov.cz/datový/číselníky/pojem/položka-číselníku"));
    }

    @Test
    void isUri_acceptsFragmentIris() {
        // RFC 3987 "absolute" IRIs exclude a fragment, so isUri must not rely on isAbsolute().
        assertTrue(DataTypeConverter.isUri("http://www.w3.org/2000/01/rdf-schema#Literal"));
        assertTrue(DataTypeConverter.isUri("http://www.w3.org/2001/XMLSchema#string"));
        assertTrue(DataTypeConverter.isUri("https://slovník.gov.cz/příkladový-slovník/pojem/text-p#fragment"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not-a-uri", "example.com", "https://"})
    void isUri_rejectsNonIris(String value) {
        assertFalse(DataTypeConverter.isUri(value));
    }

    @Test
    void isValidXSDType_knowsBuiltInLocalNames() {
        assertTrue(DataTypeConverter.isValidXSDType("string"));
        assertTrue(DataTypeConverter.isValidXSDType("dateTimeStamp"));
        assertFalse(DataTypeConverter.isValidXSDType("xsd:string"));
        assertFalse(DataTypeConverter.isValidXSDType("text"));
        assertFalse(DataTypeConverter.isValidXSDType(null));
    }

    @Test
    void getXSDTypeURI_expandsLocalAndPrefixedNames() {
        assertEquals(XSD.xstring.getURI(), DataTypeConverter.getXSDTypeURI("string"));
        assertEquals(XSD.xboolean.getURI(), DataTypeConverter.getXSDTypeURI(" xsd:boolean "));
    }

    @Test
    void getXSDTypeURI_returnsHttpIrisUnchanged() {
        assertEquals("http://example.com/type", DataTypeConverter.getXSDTypeURI("http://example.com/type"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "text", "xsd:text"})
    void getXSDTypeURI_returnsNullForBlankOrUnknownTypes(String type) {
        assertNull(DataTypeConverter.getXSDTypeURI(type));
    }
}