package com.dia.ismdtoolbackend.utility.upload;

import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.rdf.model.Model;
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
}
