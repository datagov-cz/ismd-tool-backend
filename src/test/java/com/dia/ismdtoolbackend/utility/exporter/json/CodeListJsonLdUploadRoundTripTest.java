package com.dia.ismdtoolbackend.utility.exporter.json;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static com.dia.constants.VocabularyConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Upload parses JSON-LD generically through Jena — there is no key-level deserializer — so the
 * code-list structure only survives if the OFN context expands it to the same triples the write
 * paths produce. This pins that expansion using the bundled copy of the published context.
 */
@DisplayName("Code list - JSON-LD upload round-trip")
class CodeListJsonLdUploadRoundTripTest {

    private static final String CONCEPT_IRI =
            "https://slovník.gov.cz/datový/turistické-cíle/pojem/typ-turistického-cíle";
    private static final String CODE_LIST_IRI =
            "https://data.mvcr.gov.cz/zdroj/číselníky/typy-turistických-cílů";
    private static final String NKOD_DATASET =
            "https://data.gov.cz/zdroj/datové-sady/17651921/ff931872553062c9890157ce8615af03";

    /**
     * The document shape an editor exports and a user re-uploads. The context terms are copied
     * verbatim from the published OFN context (mirrored at
     * {@code /com/dia/context/json_ld_context.jsonld}) so the expansion under test is the real
     * one, without this test depending on network access.
     */
    private String uploadedDocument() {
        return """
                {
                  "@context": {
                    "@version": 1.1,
                    "111-2009": "https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/",
                    "slovníky": "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/",
                    "skos": "http://www.w3.org/2004/02/skos/core#",
                    "iri": "@id",
                    "typ": "@type",
                    "název": { "@id": "skos:prefLabel", "@container": "@language" },
                    "instance-definovány-číselníkem": {
                      "@id": "slovníky:má-instance-definované-číselníkem",
                      "@type": "@id",
                      "@context": {
                        "Číselník": {
                          "@id": "111-2009:číselník",
                          "@context": {
                            "datová-sada-v-nkod": {
                              "@id": "111-2009:má-v-nkod-zastřešující-datovou-sadu",
                              "@type": "@id"
                            }
                          }
                        }
                      }
                    }
                  },
                  "iri": "%s",
                  "název": { "cs": "Typ turistického cíle" },
                  "instance-definovány-číselníkem": {
                    "iri": "%s",
                    "typ": "Číselník",
                    "datová-sada-v-nkod": "%s"
                  }
                }
                """.formatted(CONCEPT_IRI, CODE_LIST_IRI, NKOD_DATASET);
    }

    @Test
    @DisplayName("Bundled context fixture still declares the code-list terms")
    void bundledContextDeclaresCodeListTerms() {
        String context = readBundledContext();
        assertTrue(context.contains("\"instance-definovány-číselníkem\""),
                "context fixture must declare the code-list term");
        assertTrue(context.contains("111-2009:má-v-nkod-zastřešující-datovou-sadu"),
                "context fixture must map the NKOD dataset predicate");
        assertTrue(context.contains("111-2009:číselník"),
                "context fixture must map the číselník type");
    }

    private String readBundledContext() {
        try (InputStream in = getClass().getResourceAsStream("/com/dia/context/json_ld_context.jsonld")) {
            assertNotNull(in, "bundled JSON-LD context fixture must be present");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("could not read bundled context", e);
        }
    }

    @Test
    @DisplayName("Uploaded JSON-LD expands to a named číselník subject with its dataset")
    void uploadedJsonLdExpandsToNamedCiselnik() {
        Model parsed = ModelFactory.createDefaultModel();
        RDFDataMgr.read(parsed,
                new ByteArrayInputStream(uploadedDocument().getBytes(StandardCharsets.UTF_8)),
                Lang.JSONLD);

        Resource concept = parsed.getResource(CONCEPT_IRI);
        Property instanceDefinedBy = parsed.createProperty(
                OFN_NAMESPACE + MA_INSTANCE_DEFINOVANE_CISELNIKEM);

        assertTrue(concept.hasProperty(instanceDefinedBy),
                "concept must link to the číselník after upload");

        Resource codeList = concept.getProperty(instanceDefinedBy).getObject().asResource();
        assertFalse(codeList.isAnon(), "číselník must expand to a named subject, not a blank node");
        assertEquals(CODE_LIST_IRI, codeList.getURI());

        assertTrue(codeList.hasProperty(RDF.type,
                        parsed.getResource(OFN_NAMESPACE_LEGAL + CISELNIK)),
                "číselník must carry rdf:type l111-2009:číselník");

        Property datasetProp = parsed.createProperty(
                OFN_NAMESPACE_LEGAL + MA_V_NKOD_ZASTRESUJICI_DATOVOU_SADU);
        assertEquals(NKOD_DATASET,
                codeList.getProperty(datasetProp).getObject().asResource().getURI(),
                "číselník must carry its NKOD dataset");
    }

    @Test
    @DisplayName("Parsed triples match what the reader expects, so upload feeds the detail path")
    void parsedTriplesAreReadableByConceptProcessor() {
        Model parsed = ModelFactory.createDefaultModel();
        RDFDataMgr.read(parsed,
                new ByteArrayInputStream(uploadedDocument().getBytes(StandardCharsets.UTF_8)),
                Lang.JSONLD);

        // ConceptProcessor.addCodeListDatasetProperty reads via its own private copy of the
        // 111/2009 namespace. Asserting the literal here catches that copy drifting away from
        // OFN_NAMESPACE_LEGAL, which would silently break the read path.
        String readerNamespace = "https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/";
        assertEquals(readerNamespace, OFN_NAMESPACE_LEGAL,
                "reader namespace and OFN_NAMESPACE_LEGAL must stay identical");

        Resource codeList = parsed.getResource(CODE_LIST_IRI);
        assertTrue(codeList.hasProperty(RDF.type,
                parsed.getResource(readerNamespace + CISELNIK)));
        assertTrue(codeList.hasProperty(parsed.createProperty(
                readerNamespace + MA_V_NKOD_ZASTRESUJICI_DATOVOU_SADU)));
    }
}
