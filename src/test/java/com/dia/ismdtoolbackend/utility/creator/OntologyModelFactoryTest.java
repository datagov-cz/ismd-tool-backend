package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static com.dia.ismdtoolbackend.constants.VocabularyConstants.*;
import static org.junit.jupiter.api.Assertions.*;

class OntologyModelFactoryTest {

    private static final String IRI = "https://slovník.gov.cz/test/slovnik";

    @Test
    void creationMomentIsATypedDateTimeStampWithOffsetAndSeconds() {
        OntModel model = OntologyModelFactory.create(IRI, ontology());

        Resource ontology = model.getResource(IRI);
        Resource moment = ontology.getPropertyResourceValue(model.createProperty(SLOVNIKY_NS + OKAMZIK_VYTVORENI));
        assertNotNull(moment);
        assertTrue(moment.hasProperty(RDF.type, model.createResource(CAS_NS + CASOVY_OKAMZIK)));

        Literal value = moment.getProperty(model.createProperty(CAS_NS + DATUM_A_CAS)).getLiteral();
        assertEquals(XSDDatatype.XSDdateTimeStamp.getURI(), value.getDatatypeURI());
        assertTrue(XSDDatatype.XSDdateTimeStamp.isValid(value.getLexicalForm()), value.getLexicalForm());
        assertTrue(value.getLexicalForm().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(Z|[+-]\\d{2}:\\d{2})"),
                value.getLexicalForm());
        assertDoesNotThrow(() -> OffsetDateTime.parse(value.getLexicalForm()));
    }

    @Test
    void graphHoldsOnlyTheOntologyAndItsCreationMoment() {
        OntModel model = OntologyModelFactory.create(IRI, ontology());

        assertTrue(model.listSubjectsWithProperty(RDF.type, OWL2.Class).toList().isEmpty(),
                "no schema class declarations are written into a new ontology graph");
        assertEquals(2, model.listSubjects().toList().size());
    }

    private static OntologyCreateModel ontology() {
        NameModel name = new NameModel();
        name.setName(Map.of("cs", "Testovací slovník"));
        OntologyCreateModel model = new OntologyCreateModel();
        model.setNameModel(name);
        return model;
    }
}