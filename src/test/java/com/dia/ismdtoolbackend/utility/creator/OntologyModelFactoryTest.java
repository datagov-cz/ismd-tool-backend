package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import com.dia.ismdtoolbackend.ofn.OfnIris;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** The vocabulary triples a new ISMD vocabulary starts with. */
class OntologyModelFactoryTest {

    private static final String VOCABULARY = "https://slovník.gov.cz/datový/test-slovník";

    @Test
    void vocabularyIsWrittenInTheOfnShapeOnly() {
        OntologyCreateModel create = new OntologyCreateModel();
        NameModel name = new NameModel();
        name.setName(Map.of("cs", " Testovací slovník ", "en", "Test vocabulary"));
        create.setNameModel(name);
        DescriptionModel description = new DescriptionModel();
        description.setDescription(Map.of("cs", "Popis"));
        create.setDescriptionModel(description);
        Clock clock = Clock.fixed(Instant.parse("2026-10-28T08:00:00Z"), ZoneOffset.UTC);

        Model model = OntologyModelFactory.create(VOCABULARY, create, clock);

        Model expected = Ttl.parse("""
                <%s> a owl:Ontology, skos:ConceptScheme, slovníky:slovník ;
                    skos:prefLabel "Testovací slovník"@cs, "Test vocabulary"@en ;
                    dct:description "Popis"@cs ;
                    slovníky:okamžik-vytvoření [ a čas:časový-okamžik ;
                        čas:datum-a-čas "2026-10-28T09:00:00+01:00"^^xsd:dateTimeStamp ] .
                """.formatted(VOCABULARY));
        assertThat(model.isIsomorphicWith(expected))
                .as("missing: %s%nextra: %s", expected.difference(model), model.difference(expected))
                .isTrue();
    }

    @Test
    void creationMomentIsATypedDateTimeStampWithOffsetAndSeconds() {
        OntModel model = OntologyModelFactory.create(VOCABULARY, named());

        Resource moment = model.getResource(VOCABULARY)
                .getPropertyResourceValue(model.createProperty(OfnIris.OKAMZIK_VYTVORENI));
        assertThat(moment).isNotNull();

        Literal value = moment.getProperty(model.createProperty(OfnIris.CAS + "datum-a-čas")).getLiteral();
        assertThat(value.getDatatypeURI()).isEqualTo(XSDDatatype.XSDdateTimeStamp.getURI());
        assertThat(XSDDatatype.XSDdateTimeStamp.isValid(value.getLexicalForm())).as(value.getLexicalForm()).isTrue();
        assertThat(value.getLexicalForm()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(Z|[+-]\\d{2}:\\d{2})");
        assertThatCode(() -> OffsetDateTime.parse(value.getLexicalForm())).doesNotThrowAnyException();
    }

    @Test
    void graphHoldsOnlyTheOntologyAndItsCreationMoment() {
        OntModel model = OntologyModelFactory.create(VOCABULARY, named());

        assertThat(model.listSubjectsWithProperty(RDF.type, OWL2.Class).toList())
                .as("no schema class declarations are written into a new ontology graph")
                .isEmpty();
        assertThat(model.listSubjects().toList()).hasSize(2);
    }

    private static OntologyCreateModel named() {
        NameModel name = new NameModel();
        name.setName(Map.of("cs", "Testovací slovník"));
        OntologyCreateModel model = new OntologyCreateModel();
        model.setNameModel(name);
        return model;
    }
}
