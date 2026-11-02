package com.dia.ismdtoolbackend.utility.exporter.json;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.utility.exporter.RdfTestModelFactory.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConceptJsonMapper - Domain and Range")
class ConceptDomainRangeTest {

    private ConceptJsonMapper processor;

    @BeforeEach
    void setUp() {
        processor = new ConceptJsonMapper();
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

        assertThat(result.get(DEFINICNI_OBOR)).isEqualTo(domainClass.getURI());
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

        assertThat(result.get(OBOR_HODNOT)).isEqualTo(rangeClass.getURI());
    }

    @Test
    @DisplayName("DatatypeProperty with XSD range uses xsd: prefix")
    void datatypePropertyXsdRange() {
        OntModel model = createDefaultModel();
        Resource domainClass = addOwlClass(model, "osoba", "Osoba");
        Resource prop = addDatatypeProperty(model, "jmeno", "jméno", domainClass, "string");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, prop.getURI());

        assertThat(result.get(OBOR_HODNOT)).isEqualTo("xsd:string");
    }

    @Test
    @DisplayName("DatatypeProperty with xsd:date uses xsd: abbreviation")
    void datatypePropertyXsdDate() {
        OntModel model = createDefaultModel();
        Resource domainClass = addOwlClass(model, "osoba", "Osoba");
        Resource prop = addDatatypeProperty(model, "datum-narozeni", "datum narození", domainClass, "date");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, prop.getURI());

        assertThat(result.get(OBOR_HODNOT)).isEqualTo("xsd:date");
    }

    @Test
    @DisplayName("DatatypeProperty with xsd:boolean uses xsd: abbreviation")
    void datatypePropertyXsdBoolean() {
        OntModel model = createDefaultModel();
        Resource domainClass = addOwlClass(model, "osoba", "Osoba");
        Resource prop = addDatatypeProperty(model, "je-aktivni", "je aktivní", domainClass, "boolean");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, prop.getURI());

        assertThat(result.get(OBOR_HODNOT)).isEqualTo("xsd:boolean");
    }

    @Test
    @DisplayName("No domain/range → fields absent")
    void noDomainRange_fieldsAbsent() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertThat(result).doesNotContainKey(DEFINICNI_OBOR);
        assertThat(result).doesNotContainKey(OBOR_HODNOT);
    }

    @Test
    @DisplayName("Domain present but no range → only definiční-obor")
    void domainOnly() {
        OntModel model = createDefaultModel();
        Resource domainClass = addOwlClass(model, "osoba", "Osoba");
        Resource relation = addObjectProperty(model, "ma-vlastnost", "má vlastnost", domainClass, null);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, relation.getURI());

        assertThat(result).containsKey(DEFINICNI_OBOR);
        assertThat(result).doesNotContainKey(OBOR_HODNOT);
    }
}
