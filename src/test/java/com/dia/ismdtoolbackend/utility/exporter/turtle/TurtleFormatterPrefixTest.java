package com.dia.ismdtoolbackend.utility.exporter.turtle;

import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.utility.exporter.RdfTestModelFactory.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TurtleFormatterUtil - OFN Prefix Setup")
class TurtleFormatterPrefixTest {

    private Model result;

    @BeforeEach
    void setUp() {
        var model = createDefaultModel();
        result = TurtleFormatterUtil.transformToOFNFormat(model);
    }

    @ParameterizedTest(name = "Prefix {0} maps to correct namespace")
    @CsvSource({
            "dct, http://purl.org/dc/terms/",
            "skos, http://www.w3.org/2004/02/skos/core#",
            "xsd, http://www.w3.org/2001/XMLSchema#",
            "rdf, http://www.w3.org/1999/02/22-rdf-syntax-ns#",
            "rdfs, http://www.w3.org/2000/01/rdf-schema#",
            "schema, http://schema.org/"
    })
    @DisplayName("Standard prefixes have correct URIs")
    void standardPrefixes(String prefix, String expectedUri) {
        Map<String, String> prefixes = result.getNsPrefixMap();
        assertEquals(expectedUri, prefixes.get(prefix));
    }

    @Test
    @DisplayName("slovníky prefix maps to OFN_NAMESPACE")
    void slovnikyPrefix() {
        assertEquals(OFN_NAMESPACE, result.getNsPrefixMap().get("slovníky"));
    }

    @Test
    @DisplayName("čas prefix maps to CAS_NS")
    void casPrefix() {
        assertEquals(CAS_NS, result.getNsPrefixMap().get("čas"));
    }

    @Test
    @DisplayName("All 16 OFN prefixes are present")
    void allPrefixesPresent() {
        Map<String, String> prefixes = result.getNsPrefixMap();
        String[] expectedPrefixes = {
                "dct", "owl", "rdf", "rdfs", "skos", "slovníky", "vsgov", "xsd",
                "čas", "a104", "l111-2009", "schema",
                "typ-obsahu-údajů", "způsoby-sdílení-údajů", "způsoby-získání-údajů",
                "ustanovení-dokládající-neveřejnost-pojmu"
        };
        for (String prefix : expectedPrefixes) {
            assertNotNull(prefixes.get(prefix), "Missing prefix: " + prefix);
        }
    }
}
