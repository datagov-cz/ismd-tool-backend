package com.dia.ismdtoolbackend.utility.upload;

import com.dia.ismdtoolbackend.exception.OntologyUploadMissingIriException;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadGraphNameResolverTest {

    @Test
    void prefersOwlOntology_thenConceptScheme() {
        assertThat(UploadGraphNameResolver.resolve(Ttl.parse("""
                <https://example.org/schema> a skos:ConceptScheme .
                <https://example.org/ontology> a owl:Ontology .
                """))).isEqualTo("https://example.org/ontology");
        assertThat(UploadGraphNameResolver.resolve(Ttl.parse("<https://example.org/schema> a skos:ConceptScheme ."))
        ).isEqualTo("https://example.org/schema");
    }

    @Test
    void withoutVocabulary_failsInsteadOfInventingAName() {
        assertThatThrownBy(() -> UploadGraphNameResolver.resolve(Ttl.parse("[] a owl:Ontology . pojem:a a skos:Concept .")))
                .isInstanceOf(OntologyUploadMissingIriException.class);
    }
}
