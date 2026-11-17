package com.dia.ismdtoolbackend.ofn;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfnJsonLdParserTest {

    @Test
    void ofnContext_isServedFromTheBundledCopy() {
        String json = """
                {"@context": "%s", "iri": "https://slovník.gov.cz/test", "typ": ["Slovník"],
                 "název": {"cs": "Test"}}""".formatted(OfnSpecification.bundled().contextUrl());
        Model model = ModelFactory.createDefaultModel();

        OfnJsonLdParser.parse(json, model);

        assertThat(model.size()).isEqualTo(2);
    }

    @Test
    void anyOtherRemoteContext_isRefusedByTheLoader_notFetched() {
        String json = """
                {"@context": "https://example.org/kontext.jsonld", "@id": "https://example.org/a"}""";

        assertThatThrownBy(() -> OfnJsonLdParser.parse(json, ModelFactory.createDefaultModel()))
                .hasStackTraceContaining("Vzdálený JSON-LD kontext není povolen");
    }
}
