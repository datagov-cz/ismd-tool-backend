package com.dia.ismdtoolbackend.query;

import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NKDSPARQLConstructQueryTest {

    private static final String ONTOLOGY_IRI = "https://example.org/ontology/1";
    private static final String CONCEPT_IRI_A = "https://example.org/ontology/1/pojem/a";
    private static final String CONCEPT_IRI_B = "https://example.org/ontology/1/pojem/b";
    private static final String OTHER_ONTOLOGY_CONCEPT_IRI = "https://example.org/ontology/2/pojem/x";

    private static final String SKOS_IN_SCHEME = "http://www.w3.org/2004/02/skos/core#inScheme";
    private static final String SKOS_PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";
    private static final String OWL_ONTOLOGY = "http://www.w3.org/2002/07/owl#Ontology";
    private static final String OFN_NON_LEGAL_SOURCE =
            "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/související-nelegislativní-zdroj";
    private static final String SCHEMA_URL = "http://schema.org/url";

    // ── Concept fetch (existing query) ─────────────────────────────────

    @Test
    void buildConstructQuery_fetchesConceptAndBlankNodeClosure() {
        Model dataset = buildDataset();

        Model result = runConstruct(dataset, NKDSPARQLConstructQuery.buildConstructQuery(CONCEPT_IRI_A));

        Resource conceptA = result.createResource(CONCEPT_IRI_A);
        Property prefLabel = result.createProperty(SKOS_PREF_LABEL);
        assertThat(result.contains(conceptA, prefLabel)).isTrue();

        // Blank-node non-legal source and its nested schema:url must be included
        Property nonLegalSource = result.createProperty(OFN_NON_LEGAL_SOURCE);
        assertThat(result.listObjectsOfProperty(conceptA, nonLegalSource).hasNext()).isTrue();
        assertThat(result.listStatements(null, result.createProperty(SCHEMA_URL), (RDFNode) null).hasNext())
                .as("Blank-node closure should include nested schema:url triples")
                .isTrue();
    }

    // ── Ontology fetch (updated query) ─────────────────────────────────

    @Test
    void buildOntologyConstructQuery_fetchesOntologyMetadata() {
        Model dataset = buildDataset();

        Model result = runConstruct(dataset, NKDSPARQLConstructQuery.buildOntologyConstructQuery(ONTOLOGY_IRI));

        Resource ontology = result.createResource(ONTOLOGY_IRI);
        assertThat(result.contains(ontology, RDF.type, result.createResource(OWL_ONTOLOGY))).isTrue();
        assertThat(result.contains(ontology, result.createProperty(SKOS_PREF_LABEL))).isTrue();
    }

    @Test
    void buildOntologyConstructQuery_fetchesAllInSchemeConcepts() {
        Model dataset = buildDataset();

        Model result = runConstruct(dataset, NKDSPARQLConstructQuery.buildOntologyConstructQuery(ONTOLOGY_IRI));

        Resource conceptA = result.createResource(CONCEPT_IRI_A);
        Resource conceptB = result.createResource(CONCEPT_IRI_B);
        Property prefLabel = result.createProperty(SKOS_PREF_LABEL);

        assertThat(result.contains(conceptA, prefLabel))
                .as("Concept A belongs to the ontology via skos:inScheme and must be fetched")
                .isTrue();
        assertThat(result.contains(conceptB, prefLabel))
                .as("Concept B belongs to the ontology via skos:inScheme and must be fetched")
                .isTrue();
    }

    @Test
    void buildOntologyConstructQuery_includesConceptBlankNodeClosure() {
        Model dataset = buildDataset();

        Model result = runConstruct(dataset, NKDSPARQLConstructQuery.buildOntologyConstructQuery(ONTOLOGY_IRI));

        // The nested schema:url triple sits on a blank node that is object of
        // concept A's non-legal source. It must survive the CONSTRUCT.
        assertThat(result.listStatements(null, result.createProperty(SCHEMA_URL), (RDFNode) null).hasNext())
                .as("One-hop blank-node closure for concepts must be included")
                .isTrue();
    }

    @Test
    void buildOntologyConstructQuery_excludesOtherOntologyConcepts() {
        Model dataset = buildDataset();

        Model result = runConstruct(dataset, NKDSPARQLConstructQuery.buildOntologyConstructQuery(ONTOLOGY_IRI));

        Resource otherConcept = result.createResource(OTHER_ONTOLOGY_CONCEPT_IRI);
        assertThat(result.contains(otherConcept, result.createProperty(SKOS_PREF_LABEL)))
                .as("Concepts belonging to a different ontology must NOT be fetched")
                .isFalse();
    }

    // ── Dataset builder ────────────────────────────────────────────────

    /**
     * Builds an in-memory dataset with:
     *   - Ontology 1: two concepts (A, B), concept A has a blank-node non-legal source.
     *   - Ontology 2: one concept (X) — must be excluded from ontology-1 fetch.
     */
    private Model buildDataset() {
        Model model = ModelFactory.createDefaultModel();

        Resource ontology = model.createResource(ONTOLOGY_IRI);
        ontology.addProperty(RDF.type, model.createResource(OWL_ONTOLOGY));
        ontology.addProperty(model.createProperty(SKOS_PREF_LABEL), "Testovací slovník", "cs");

        Resource skosConceptType = model.createResource("http://www.w3.org/2004/02/skos/core#Concept");
        Property inScheme = model.createProperty(SKOS_IN_SCHEME);
        Property prefLabel = model.createProperty(SKOS_PREF_LABEL);

        Resource conceptA = model.createResource(CONCEPT_IRI_A);
        conceptA.addProperty(RDF.type, skosConceptType);
        conceptA.addProperty(inScheme, ontology);
        conceptA.addProperty(prefLabel, "Pojem A", "cs");

        // Blank-node non-legal source on concept A
        Resource bnode = model.createResource();
        bnode.addProperty(model.createProperty(SCHEMA_URL), "https://example.org/doc/a.pdf");
        conceptA.addProperty(model.createProperty(OFN_NON_LEGAL_SOURCE), bnode);

        Resource conceptB = model.createResource(CONCEPT_IRI_B);
        conceptB.addProperty(RDF.type, skosConceptType);
        conceptB.addProperty(inScheme, ontology);
        conceptB.addProperty(prefLabel, "Pojem B", "cs");

        // A concept in a different ontology — must be excluded
        Resource otherOntology = model.createResource("https://example.org/ontology/2");
        Resource conceptX = model.createResource(OTHER_ONTOLOGY_CONCEPT_IRI);
        conceptX.addProperty(RDF.type, skosConceptType);
        conceptX.addProperty(inScheme, otherOntology);
        conceptX.addProperty(prefLabel, "Pojem X", "cs");

        return model;
    }

    private Model runConstruct(Model dataset, String sparql) {
        try (var qExec = QueryExecutionFactory.create(QueryFactory.create(sparql), dataset)) {
            return qExec.execConstruct();
        }
    }
}
