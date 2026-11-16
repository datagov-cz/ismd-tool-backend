package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.testsupport.rdf.JsonLd;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import com.dia.ismdtoolbackend.utility.exporter.json.JsonExporter;
import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static com.dia.ismdtoolbackend.testsupport.rdf.RdfAssert.assertIsomorphic;

/**
 * A JSON export uploaded again parses back to the vocabulary's triples, timestamps included
 * ({@code vytvořeno} / {@code aktualizováno}). The context is inlined so parsing stays offline.
 */
@DisplayName("UploadFileReader - JSON-LD re-upload of an export")
class UploadFileReaderJsonLdTest {

    private final UploadFileReader reader = new UploadFileReader("10MB", 10);

    @Test
    @DisplayName("an exported vocabulary reads back with its timestamps")
    void exportReadsBackWithTimestamps() throws Exception {
        String exported = new JsonExporter().exportToJson(Ttl.parseOnt("""
                ex: a owl:Ontology ;
                    skos:prefLabel "Slovník"@cs ;
                    slovníky:okamžik-vytvoření [ a čas:časový-okamžik ; čas:datum-a-čas "2025-01-01T10:00:00Z" ] ;
                    slovníky:okamžik-poslední-změny [ a čas:časový-okamžik ; čas:datum "2025-02-01" ] .
                """));
        MockMultipartFile file = new MockMultipartFile("file", "slovník.jsonld", "application/ld+json",
                JsonLd.withBundledContext(exported).getBytes(StandardCharsets.UTF_8));

        Model uploaded = reader.read(file);

        assertIsomorphic("""
                ex: a slovníky:slovník ;
                    skos:prefLabel "Slovník"@cs ;
                    slovníky:okamžik-vytvoření [ a čas:časový-okamžik ;
                        čas:datum-a-čas "2025-01-01T10:00:00Z"^^xsd:dateTimeStamp ] ;
                    slovníky:okamžik-poslední-změny [ a čas:časový-okamžik ; čas:datum "2025-02-01"^^xsd:date ] .
                """, uploaded);
    }
}
