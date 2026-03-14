package com.dia.ismdtoolbackend.utility.exporter.json;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.utility.exporter.RdfTestModelFactory.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConceptProcessor - Domain and Range")
class ConceptDomainRangeTest {

    private ConceptProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ConceptProcessor();
    }

    @Test
    @DisplayName("ObjectProperty domain produces definiční-obor")
    void objectPropertyDomain() {
        OntModel model = createDefaultModel();
        Resource domainClass = addOwlClass(model, "osoba", "Osoba");
        Resource rangeClass = addOwlClass(model, "adresa", "Adresa");
        Resource relation = addObjectProperty(model, "ma-adresu", "má adresu", domainClass, rangeClass);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, relation.getURI());

        assertEquals(domainClass.getURI(), result.get(DEFINICNI_OBOR));
    }

    @Test
    @DisplayName("ObjectProperty range produces obor-hodnot")
    void objectPropertyRange() {
        OntModel model = createDefaultModel();
        Resource domainClass = addOwlClass(model, "osoba", "Osoba");
        Resource rangeClass = addOwlClass(model, "adresa", "Adresa");
        Resource relation = addObjectProperty(model, "ma-adresu", "má adresu", domainClass, rangeClass);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, relation.getURI());

        assertEquals(rangeClass.getURI(), result.get(OBOR_HODNOT));
    }

    @Test
    @DisplayName("DatatypeProperty with XSD range uses xsd: prefix")
    void datatypePropertyXsdRange() {
        OntModel model = createDefaultModel();
        Resource domainClass = addOwlClass(model, "osoba", "Osoba");
        Resource prop = addDatatypeProperty(model, "jmeno", "jméno", domainClass, "string");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, prop.getURI());

        assertEquals("xsd:string", result.get(OBOR_HODNOT));
    }

    @Test
    @DisplayName("DatatypeProperty with xsd:date uses xsd: abbreviation")
    void datatypePropertyXsdDate() {
        OntModel model = createDefaultModel();
        Resource domainClass = addOwlClass(model, "osoba", "Osoba");
        Resource prop = addDatatypeProperty(model, "datum-narozeni", "datum narození", domainClass, "date");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, prop.getURI());

        assertEquals("xsd:date", result.get(OBOR_HODNOT));
    }

    @Test
    @DisplayName("DatatypeProperty with xsd:boolean uses xsd: abbreviation")
    void datatypePropertyXsdBoolean() {
        OntModel model = createDefaultModel();
        Resource domainClass = addOwlClass(model, "osoba", "Osoba");
        Resource prop = addDatatypeProperty(model, "je-aktivni", "je aktivní", domainClass, "boolean");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, prop.getURI());

        assertEquals("xsd:boolean", result.get(OBOR_HODNOT));
    }

    @Test
    @DisplayName("No domain/range → fields absent")
    void noDomainRange_fieldsAbsent() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertFalse(result.containsKey(DEFINICNI_OBOR));
        assertFalse(result.containsKey(OBOR_HODNOT));
    }

    @Test
    @DisplayName("Domain present but no range → only definiční-obor")
    void domainOnly() {
        OntModel model = createDefaultModel();
        Resource domainClass = addOwlClass(model, "osoba", "Osoba");
        Resource relation = addObjectProperty(model, "ma-vlastnost", "má vlastnost", domainClass, null);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, relation.getURI());

        assertTrue(result.containsKey(DEFINICNI_OBOR));
        assertFalse(result.containsKey(OBOR_HODNOT));
    }
}
