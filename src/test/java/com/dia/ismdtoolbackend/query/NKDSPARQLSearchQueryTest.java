package com.dia.ismdtoolbackend.query;

import com.dia.ismdtoolbackend.enums.RelationType;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NKDSPARQLSearchQueryTest {

    @Test
    void buildOntologySearchQuery_containsBifContainsWithWildcard() {
        String query = NKDSPARQLSearchQuery.buildOntologySearchQuery("osoba", "cs", 20, 0);

        assertTrue(query.contains("bif:contains"));
        assertTrue(query.contains("\"osoba*\""));
        assertTrue(query.contains("owl:Ontology"));
        assertTrue(query.contains("LIMIT 20"));
        assertTrue(query.contains("OFFSET 0"));
    }

    @Test
    void buildOntologySearchQuery_searchesCorrectFields() {
        String query = NKDSPARQLSearchQuery.buildOntologySearchQuery("test", "cs", 10, 0);

        assertTrue(query.contains("skos:prefLabel"));
        assertTrue(query.contains("dcterms:title"));
        assertTrue(query.contains("dcterms:description"));
    }

    @Test
    void buildOntologySearchQuery_appliesLanguagePreference() {
        String query = NKDSPARQLSearchQuery.buildOntologySearchQuery("test", "en", 10, 0);

        assertTrue(query.contains("LANG(?prefLabel) = \"en\""));
    }

    @Test
    void buildOntologySearchQuery_appliesPagination() {
        String query = NKDSPARQLSearchQuery.buildOntologySearchQuery("test", "cs", 50, 100);

        assertTrue(query.contains("LIMIT 50"));
        assertTrue(query.contains("OFFSET 100"));
    }

    @Test
    void buildConceptSearchQuery_containsBifContainsWithWildcard() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery("osoba", "cs", 20, 0, null, null);

        assertTrue(query.contains("bif:contains"));
        assertTrue(query.contains("\"osoba*\""));
        assertTrue(query.contains("skos:Concept"));
        assertTrue(query.contains("LIMIT 20"));
        assertTrue(query.contains("OFFSET 0"));
    }

    @Test
    void buildConceptSearchQuery_searchesCorrectFields() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery("test", "cs", 10, 0, null, null);

        assertTrue(query.contains("skos:prefLabel"));
        assertTrue(query.contains("skos:altLabel"));
        assertTrue(query.contains("dcterms:description"));
        assertTrue(query.contains("skos:definition"));
    }

    @Test
    void buildConceptSearchQuery_withOntologyIriFilter_usesValuesClause() {
        List<String> ontologyIris = List.of(
                "https://slovnik.gov.cz/datovy/osoby",
                "https://slovnik.gov.cz/datovy/adresy"
        );

        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery("osoba", "cs", 20, 0, ontologyIris, null);

        assertTrue(query.contains("VALUES ?ontology"));
        assertTrue(query.contains("<https://slovnik.gov.cz/datovy/osoby>"));
        assertTrue(query.contains("<https://slovnik.gov.cz/datovy/adresy>"));
        // Must NOT use FILTER(?ontology IN (...))
        assertFalse(query.contains("FILTER(?ontology IN"));
    }

    @Test
    void buildConceptSearchQuery_withoutOntologyIriFilter_noValuesClause() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery("osoba", "cs", 20, 0, null, null);

        assertFalse(query.contains("VALUES ?ontology"));
    }

    @Test
    void buildConceptSearchQuery_withEmptyOntologyIriList_noValuesClause() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery("osoba", "cs", 20, 0, List.of(), null);

        assertFalse(query.contains("VALUES ?ontology"));
    }

    @Test
    void buildConceptSearchQuery_withSingleRelationType_usesFilterExists() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery(
                "osoba", "cs", 20, 0, null, List.of(RelationType.SUBCLASS));

        assertTrue(query.contains("FILTER EXISTS { ?resource rdfs:subClassOf ?x }"));
    }

    @Test
    void buildConceptSearchQuery_withMultipleRelationTypes_combinesWithOr() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery(
                "osoba", "cs", 20, 0, null, List.of(RelationType.SUBCLASS, RelationType.EXACT_MATCH));

        assertTrue(query.contains("EXISTS { ?resource rdfs:subClassOf ?x }"));
        assertTrue(query.contains("EXISTS { ?resource skos:exactMatch ?x }"));
        assertTrue(query.contains("||"));
    }

    @Test
    void buildConceptSearchQuery_allRelationTypesMapped() {
        for (RelationType rt : RelationType.values()) {
            String query = NKDSPARQLSearchQuery.buildConceptSearchQuery(
                    "test", "cs", 10, 0, null, List.of(rt));
            assertTrue(query.contains("FILTER EXISTS"), "Missing FILTER EXISTS for " + rt);
        }
    }

    @Test
    void buildConceptSearchQuery_superclass_hasCorrectPattern() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery(
                "test", "cs", 10, 0, null, List.of(RelationType.SUPERCLASS));

        assertTrue(query.contains("{ ?x rdfs:subClassOf ?resource }"));
    }

    @Test
    void buildConceptSearchQuery_propertyOf_usesDomain() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery(
                "test", "cs", 10, 0, null, List.of(RelationType.PROPERTY_OF));

        assertTrue(query.contains("{ ?resource rdfs:domain ?x }"));
    }

    @Test
    void buildConceptSearchQuery_relationshipOf_usesRange() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery(
                "test", "cs", 10, 0, null, List.of(RelationType.RELATIONSHIP_OF));

        assertTrue(query.contains("{ ?resource rdfs:range ?x }"));
    }

    @Test
    void sanitizeSearchTerm_removesQuotes() {
        assertEquals("test", NKDSPARQLSearchQuery.sanitizeSearchTerm("te\"st"));
        assertEquals("test", NKDSPARQLSearchQuery.sanitizeSearchTerm("te'st"));
    }

    @Test
    void sanitizeSearchTerm_removesAngleBrackets() {
        assertEquals("test", NKDSPARQLSearchQuery.sanitizeSearchTerm("te<s>t"));
    }

    @Test
    void sanitizeSearchTerm_removesBackslashes() {
        assertEquals("test", NKDSPARQLSearchQuery.sanitizeSearchTerm("te\\st"));
    }

    @Test
    void sanitizeSearchTerm_handlesNull() {
        assertEquals("", NKDSPARQLSearchQuery.sanitizeSearchTerm(null));
    }

    @Test
    void sanitizeSearchTerm_preservesCzechDiacritics() {
        assertEquals("čeština", NKDSPARQLSearchQuery.sanitizeSearchTerm("čeština"));
        assertEquals("příliš žluťoučký", NKDSPARQLSearchQuery.sanitizeSearchTerm("příliš žluťoučký"));
    }

    @Test
    void buildConceptSearchQuery_withBothFilters_includesBothClauses() {
        List<String> iris = List.of("https://example.org/ontology/1");
        List<RelationType> types = List.of(RelationType.SUBCLASS);

        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery("test", "cs", 10, 0, iris, types);

        assertTrue(query.contains("VALUES ?ontology"));
        assertTrue(query.contains("FILTER EXISTS"));
    }

    @Test
    void buildConceptSearchQuery_appliesLanguagePreference() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery("test", "en", 10, 0, null, null);

        assertTrue(query.contains("LANG(?prefLabel) = \"en\""));
    }

    @Test
    void buildConceptSearchQuery_includesOntologyVariable() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery("test", "cs", 10, 0, null, null);

        assertTrue(query.contains("?ontology"));
        assertTrue(query.contains("skos:inScheme ?ontology"));
    }

    @Test
    void isSafeHttpIri_acceptsHttpAndHttps() {
        assertTrue(SparqlIriValidator.isSafeHttpIri("https://slovnik.gov.cz/datovy/osoby"));
        assertTrue(SparqlIriValidator.isSafeHttpIri("http://example.org/ontology#Thing"));
    }

    @Test
    void isSafeHttpIri_rejectsInjectionAttempts() {
        // Space + SPARQL fragment that would break out of <...>
        assertFalse(SparqlIriValidator.isSafeHttpIri(
                "http://example.org/ont } SELECT * WHERE { ?s ?p ?o"));
        assertFalse(SparqlIriValidator.isSafeHttpIri("http://example.org/>malicious"));
        assertFalse(SparqlIriValidator.isSafeHttpIri("http://example.org/a\nb"));
    }

    @Test
    void isSafeHttpIri_rejectsNonHttpSchemes() {
        assertFalse(SparqlIriValidator.isSafeHttpIri("file:///etc/passwd"));
        assertFalse(SparqlIriValidator.isSafeHttpIri("javascript:alert(1)"));
        assertFalse(SparqlIriValidator.isSafeHttpIri("urn:isbn:0451450523"));
    }

    @Test
    void isSafeHttpIri_rejectsNullAndBlank() {
        assertFalse(SparqlIriValidator.isSafeHttpIri(null));
        assertFalse(SparqlIriValidator.isSafeHttpIri(""));
        assertFalse(SparqlIriValidator.isSafeHttpIri("   "));
    }

    @Test
    void buildConceptSearchQuery_dropsMaliciousOntologyIri() {
        List<String> iris = List.of(
                "https://slovnik.gov.cz/datovy/osoby",
                "http://example.org/ont } SELECT * WHERE { ?s ?p ?o"
        );

        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery("osoba", "cs", 20, 0, iris, null);

        assertTrue(query.contains("<https://slovnik.gov.cz/datovy/osoby>"));
        assertFalse(query.contains("SELECT * WHERE { ?s ?p ?o"));
        assertFalse(query.contains("} SELECT"));
    }

    @Test
    void buildConceptSearchQuery_allInvalidOntologyIris_noValuesClause() {
        List<String> iris = List.of(
                "javascript:alert(1)",
                "http://example.org/bad }"
        );

        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery("osoba", "cs", 20, 0, iris, null);

        assertFalse(query.contains("VALUES ?ontology"));
    }
}
