package com.dia.ismdtoolbackend.utility.upload;

import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.Test;

import static com.dia.ismdtoolbackend.testsupport.rdf.RdfAssert.assertIsomorphic;
import static org.assertj.core.api.Assertions.assertThat;

class ExcludedConceptPruningTest {

    private static final String V = Ttl.VOCABULARY.substring(0, Ttl.VOCABULARY.length() - 1);

    @Test
    void removesUnreferencedExcludedConcepts_keepsReferencedOnes_andLeavesOthersAlone() {
        Model model = Ttl.parse("""
                pojem:člen a skos:Concept ; skos:inScheme <https://slovník.gov.cz/datový/test-slovník> ;
                    rdfs:subClassOf pojem:odkazovaný .
                pojem:odkazovaný a skos:Concept ; skos:prefLabel "Rodič"@cs .
                pojem:samotný a slovníky:pojem ; skos:prefLabel "Sám"@cs .
                pojem:sám-sebe a skos:Concept ; skos:related pojem:sám-sebe .
                <https://slovník.gov.cz/jiný/pojem/x> a skos:Concept .
                """);

        int pruned = ExcludedConceptPruning.apply(model, V);

        assertThat(pruned).isEqualTo(2);
        assertIsomorphic("""
                pojem:člen a skos:Concept ; skos:inScheme <https://slovník.gov.cz/datový/test-slovník> ;
                    rdfs:subClassOf pojem:odkazovaný .
                pojem:odkazovaný a skos:Concept ; skos:prefLabel "Rodič"@cs .
                <https://slovník.gov.cz/jiný/pojem/x> a skos:Concept .
                """, model);
    }

    @Test
    void removesThePrunedConceptsBlankChildren_keepingOnesReferencedFromOutside() {
        Model model = Ttl.parse("""
                pojem:samotný a slovníky:pojem ;
                    l111:je-vymezen-ustanovením-stanovujícím-jeho-neveřejnost [ a do:digitální-objekt ;
                        schema:url "https://example.org/doc"^^xsd:anyURI ;
                        čas:okamžik [ a čas:časový-okamžik ; čas:datum "2020-01-01"^^xsd:date ] ] ;
                    skos:note _:sdílený .
                pojem:člen a skos:Concept ; skos:inScheme <https://slovník.gov.cz/datový/test-slovník> ;
                    skos:note _:sdílený .
                _:sdílený rdfs:label "sdílený"@cs .
                """);

        ExcludedConceptPruning.apply(model, V);

        assertIsomorphic("""
                pojem:člen a skos:Concept ; skos:inScheme <https://slovník.gov.cz/datový/test-slovník> ;
                    skos:note _:sdílený .
                _:sdílený rdfs:label "sdílený"@cs .
                """, model);
    }

    @Test
    void ownedResourcesWithOnlyAnOwlKind_areListedAndPrunedUnlessReferenced() {
        Model model = Ttl.parse("""
                pojem:jméno a owl:DatatypeProperty ; rdfs:label "Jméno"@cs .
                pojem:zná a owl:ObjectProperty .
                pojem:člen a skos:Concept, slovníky:pojem, owl:Class ;
                    skos:inScheme <https://slovník.gov.cz/datový/test-slovník> ; skos:related pojem:zná .
                pojem:ve-schématu a owl:Class ; skos:inScheme <https://slovník.gov.cz/datový/test-slovník> .
                pojem:koncept a owl:Class, skos:Concept .
                <https://slovník.gov.cz/jiný/pojem/cizí> a owl:Class .
                """);

        assertThat(OwnedConcepts.withOnlyAnOwlKind(model, V)).extracting(Resource::getURI)
                .containsExactly(Ttl.POJEM + "jméno", Ttl.POJEM + "zná");

        ExcludedConceptPruning.apply(model, V);

        assertIsomorphic("""
                pojem:zná a owl:ObjectProperty .
                pojem:člen a skos:Concept, slovníky:pojem, owl:Class ;
                    skos:inScheme <https://slovník.gov.cz/datový/test-slovník> ; skos:related pojem:zná .
                pojem:ve-schématu a owl:Class ; skos:inScheme <https://slovník.gov.cz/datový/test-slovník> .
                <https://slovník.gov.cz/jiný/pojem/cizí> a owl:Class .
                """, model);
    }
}
