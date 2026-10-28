package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}
