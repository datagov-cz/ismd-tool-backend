package com.dia.ismdtoolbackend.utility.upload;

import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.Test;

import static com.dia.ismdtoolbackend.testsupport.rdf.RdfAssert.assertIsomorphic;
import static org.assertj.core.api.Assertions.assertThat;

class RoleMarkerDerivationTest {

    private static final String V = Ttl.VOCABULARY.substring(0, Ttl.VOCABULARY.length() - 1);

    @Test
    void addsTheMarkerOfTheSingleOwlKind_andNothingElse() {
        Model model = Ttl.parse("""
                pojem:třída a owl:Class .
                pojem:vlastnost a owl:DatatypeProperty .
                pojem:vztah a owl:ObjectProperty, slovníky:vztah .
                pojem:dvojí a owl:DatatypeProperty, owl:ObjectProperty .
                pojem:jen-značka a slovníky:třída .
                pojem:nesouhlasí a owl:ObjectProperty, slovníky:vlastnost .
                <https://slovník.gov.cz/jiný/pojem/x> a owl:Class .
                """);

        int added = RoleMarkerDerivation.apply(model, V);

        assertThat(added).isEqualTo(3);
        assertIsomorphic("""
                pojem:třída a owl:Class, slovníky:třída .
                pojem:vlastnost a owl:DatatypeProperty, slovníky:vlastnost .
                pojem:vztah a owl:ObjectProperty, slovníky:vztah .
                pojem:dvojí a owl:DatatypeProperty, owl:ObjectProperty .
                pojem:jen-značka a slovníky:třída .
                pojem:nesouhlasí a owl:ObjectProperty, slovníky:vlastnost, slovníky:vztah .
                <https://slovník.gov.cz/jiný/pojem/x> a owl:Class .
                """, model);
    }
}
