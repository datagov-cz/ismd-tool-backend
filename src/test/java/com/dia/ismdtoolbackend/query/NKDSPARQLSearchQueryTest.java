package com.dia.ismdtoolbackend.query;

import com.dia.ismdtoolbackend.enums.RelationType;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NKDSPARQLSearchQueryTest {

    private static final List<String> SAMPLE_CANDIDATE_IRIS = List.of(
            "https://example.org/ontology/1",
            "https://example.org/ontology/2");

    @Test
    void buildOntologyIriListQuery_listsAllOwlOntologies() {
        String query = NKDSPARQLSearchQuery.buildOntologyIriListQuery();

        assertThat(query).contains("owl:Ontology");
        assertThat(query).contains("?resource");
        assertThat(query)
                .as("IRI list query must not invoke the broken bif:contains planner path")
                .doesNotContain("bif:contains");
    }

    @Test
    void buildOntologySearchQuery_containsBifContainsWithWildcard() {
        String query = NKDSPARQLSearchQuery.buildOntologySearchQuery(
                "osoba", "cs", 20, 0, SAMPLE_CANDIDATE_IRIS);

        assertThat(query).contains("bif:contains");
        assertThat(query).contains("\"osoba*\"");
        assertThat(query).contains("LIMIT 20");
        assertThat(query).contains("OFFSET 0");
    }

    @Test
    void buildOntologySearchQuery_restrictsViaValuesNotTypeFilter() {
        // Virtuoso bug #960: type-narrowing with bif:contains UNION returns wrong
        // results. We restrict via VALUES ?resource over a pre-fetched candidate
        // list instead. Asserting both the shape and the absence of the broken form.
        String query = NKDSPARQLSearchQuery.buildOntologySearchQuery(
                "osoba", "cs", 20, 0, SAMPLE_CANDIDATE_IRIS);

        assertThat(query)
                .as("Ontology search must restrict via VALUES, not type-filter (Virtuoso #960)")
                .contains("VALUES ?resource");
        assertThat(query).contains("<https://example.org/ontology/1>");
        assertThat(query).contains("<https://example.org/ontology/2>");
        assertThat(query)
                .as("Type-narrowing on ontology search triggers Virtuoso planner bug")
                .doesNotContain("?resource a owl:Ontology");
    }

    @Test
    void buildOntologySearchQuery_searchesCorrectFields() {
        String query = NKDSPARQLSearchQuery.buildOntologySearchQuery(
                "test", "cs", 10, 0, SAMPLE_CANDIDATE_IRIS);

        assertThat(query).contains("skos:prefLabel");
        assertThat(query).contains("dcterms:title");
        assertThat(query).contains("dcterms:description");
    }

    @Test
    void buildOntologySearchQuery_appliesLanguagePreference() {
        String query = NKDSPARQLSearchQuery.buildOntologySearchQuery(
                "test", "en", 10, 0, SAMPLE_CANDIDATE_IRIS);

        assertThat(query).contains("LANG(?prefLabel) = \"en\"");
    }

    @Test
    void buildOntologySearchQuery_appliesPagination() {
        String query = NKDSPARQLSearchQuery.buildOntologySearchQuery(
                "test", "cs", 50, 100, SAMPLE_CANDIDATE_IRIS);

        assertThat(query).contains("LIMIT 50");
        assertThat(query).contains("OFFSET 100");
    }

    @Test
    void buildOntologySearchQuery_unsafeIriInCandidateList_isStripped() {
        List<String> mixed = List.of(
                "https://example.org/safe",
                "javascript:alert(1)",
                "https://example.org/safe2");
        String query = NKDSPARQLSearchQuery.buildOntologySearchQuery(
                "test", "cs", 10, 0, mixed);

        assertThat(query).contains("<https://example.org/safe>");
        assertThat(query).contains("<https://example.org/safe2>");
        assertThat(query).as("Unsafe IRI leaked into VALUES block").doesNotContain("javascript:");
    }

    @Test
    void buildOntologySearchCountQuery_restrictsViaValues() {
        String query = NKDSPARQLSearchQuery.buildOntologySearchCountQuery(
                "test", SAMPLE_CANDIDATE_IRIS);

        assertThat(query).contains("COUNT(DISTINCT ?resource)");
        assertThat(query).contains("VALUES ?resource");
        assertThat(query).contains("<https://example.org/ontology/1>");
        assertThat(query).doesNotContain("?resource a owl:Ontology");
    }

    @Test
    void buildConceptSearchQuery_containsBifContainsWithWildcard() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery("osoba", "cs", 20, 0, null, null, null);

        assertThat(query).contains("bif:contains");
        assertThat(query).contains("\"osoba*\"");
        assertThat(query).contains("skos:Concept");
        assertThat(query).contains("LIMIT 20");
        assertThat(query).contains("OFFSET 0");
    }

    @Test
    void buildConceptSearchQuery_searchesCorrectFields() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery("test", "cs", 10, 0, null, null, null);

        assertThat(query).contains("skos:prefLabel");
        assertThat(query).contains("skos:altLabel");
        assertThat(query).contains("dcterms:description");
        assertThat(query).contains("skos:definition");
    }

    @Test
    void buildConceptSearchQuery_withOntologyIriFilter_usesValuesClause() {
        List<String> ontologyIris = List.of(
                "https://slovnik.gov.cz/datovy/osoby",
                "https://slovnik.gov.cz/datovy/adresy"
        );

        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery("osoba", "cs", 20, 0, ontologyIris, null, null);

        assertThat(query).contains("VALUES ?ontology");
        assertThat(query).contains("<https://slovnik.gov.cz/datovy/osoby>");
        assertThat(query).contains("<https://slovnik.gov.cz/datovy/adresy>");
        // Must NOT use FILTER(?ontology IN (...))
        assertThat(query).doesNotContain("FILTER(?ontology IN");
    }

    @Test
    void buildConceptSearchQuery_withoutOntologyIriFilter_noValuesClause() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery("osoba", "cs", 20, 0, null, null, null);

        assertThat(query).doesNotContain("VALUES ?ontology");
    }

    @Test
    void buildConceptSearchQuery_withEmptyOntologyIriList_noValuesClause() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery("osoba", "cs", 20, 0, List.of(), null, null);

        assertThat(query).doesNotContain("VALUES ?ontology");
    }

    @Test
    void buildConceptSearchQuery_withSingleRelationType_usesFilterExists() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery(
                "osoba", "cs", 20, 0, null, List.of(RelationType.SUBCLASS), null);

        assertThat(query).contains("FILTER EXISTS { ?resource rdfs:subClassOf ?x }");
    }

    @Test
    void buildConceptSearchQuery_withMultipleRelationTypes_combinesWithOr() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery(
                "osoba", "cs", 20, 0, null, List.of(RelationType.SUBCLASS, RelationType.EXACT_MATCH), null);

        assertThat(query).contains("EXISTS { ?resource rdfs:subClassOf ?x }");
        assertThat(query).contains("EXISTS { ?resource skos:exactMatch ?x }");
        assertThat(query).contains("||");
    }

    @Test
    void buildConceptSearchQuery_allRelationTypesMapped() {
        for (RelationType rt : RelationType.values()) {
            String query = NKDSPARQLSearchQuery.buildConceptSearchQuery(
                    "test", "cs", 10, 0, null, List.of(rt), null);
            assertThat(query).as("Missing FILTER EXISTS for " + rt).contains("FILTER EXISTS");
        }
    }

    @Test
    void buildConceptSearchQuery_superclass_hasCorrectPattern() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery(
                "test", "cs", 10, 0, null, List.of(RelationType.SUPERCLASS), null);

        assertThat(query).contains("{ ?x rdfs:subClassOf ?resource }");
    }

    @Test
    void buildConceptSearchQuery_propertyOf_usesDomain() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery(
                "test", "cs", 10, 0, null, List.of(RelationType.PROPERTY_OF), null);

        assertThat(query).contains("{ ?resource rdfs:domain ?x }");
    }

    @Test
    void buildConceptSearchQuery_relationshipOf_usesRange() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery(
                "test", "cs", 10, 0, null, List.of(RelationType.RELATIONSHIP_OF), null);

        assertThat(query).contains("{ ?resource rdfs:range ?x }");
    }

    @Test
    void sanitizeSearchTerm_removesQuotes() {
        assertThat(NKDSPARQLSearchQuery.sanitizeSearchTerm("te\"st")).isEqualTo("test");
        assertThat(NKDSPARQLSearchQuery.sanitizeSearchTerm("te'st")).isEqualTo("test");
    }

    @Test
    void sanitizeSearchTerm_removesAngleBrackets() {
        assertThat(NKDSPARQLSearchQuery.sanitizeSearchTerm("te<s>t")).isEqualTo("test");
    }

    @Test
    void sanitizeSearchTerm_removesBackslashes() {
        assertThat(NKDSPARQLSearchQuery.sanitizeSearchTerm("te\\st")).isEqualTo("test");
    }

    @Test
    void sanitizeSearchTerm_handlesNull() {
        assertThat(NKDSPARQLSearchQuery.sanitizeSearchTerm(null)).isEqualTo("");
    }

    @Test
    void sanitizeSearchTerm_preservesCzechDiacritics() {
        assertThat(NKDSPARQLSearchQuery.sanitizeSearchTerm("čeština")).isEqualTo("čeština");
        assertThat(NKDSPARQLSearchQuery.sanitizeSearchTerm("příliš žluťoučký")).isEqualTo("příliš žluťoučký");
    }

    // ── lang sanitization (defence-in-depth: lang lands inside a SPARQL string literal) ──

    @Test
    void sanitizeLang_passesThroughValidTags() {
        assertThat(NKDSPARQLSearchQuery.sanitizeLang("cs")).isEqualTo("cs");
        assertThat(NKDSPARQLSearchQuery.sanitizeLang("en-US")).isEqualTo("en-US");
    }

    @Test
    void sanitizeLang_stripsInjectionAttempt() {
        // The sanitizer's contract is "no chars that could break out of the
        // FILTER(LANG(?x) = "…") string literal" — it strips quotes/parens/hash
        // but keeps letters. The result is a useless lang tag (won't match any
        // labels) but is structurally safe — that's the goal.
        String result = NKDSPARQLSearchQuery.sanitizeLang("cs\" ) FILTER(false) #");
        assertThat(result).as("quote must be stripped").doesNotContain("\"");
        assertThat(result).as("paren must be stripped").doesNotContain(")");
        assertThat(result).as("paren must be stripped").doesNotContain("(");
        assertThat(result).as("hash must be stripped").doesNotContain("#");
        assertThat(result).as("space must be stripped").doesNotContain(" ");
    }

    @Test
    void sanitizeLang_stripsDotsAndSpaces() {
        assertThat(NKDSPARQLSearchQuery.sanitizeLang("cs.bogus.tag")).isEqualTo("csbogustag");
        assertThat(NKDSPARQLSearchQuery.sanitizeLang("en cs")).isEqualTo("encs");
    }

    @Test
    void sanitizeLang_nullOrEmpty_defaultsToCs() {
        assertThat(NKDSPARQLSearchQuery.sanitizeLang(null)).isEqualTo("cs");
        assertThat(NKDSPARQLSearchQuery.sanitizeLang("")).isEqualTo("cs");
        assertThat(NKDSPARQLSearchQuery.sanitizeLang("\"")).isEqualTo("cs");
    }

    @Test
    void buildOntologySearchQuery_maliciousLang_isSanitized() {
        String query = NKDSPARQLSearchQuery.buildOntologySearchQuery(
                "test", "cs\" ) FILTER(false) #", 10, 0, SAMPLE_CANDIDATE_IRIS);
        // The injected payload must not appear unescaped inside the string literal.
        assertThat(query).as("Lang injection leaked into query: " + query).doesNotContain("\" ) FILTER(false)");
    }

    @Test
    void buildConceptSearchQuery_maliciousLang_isSanitized() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery(
                "test", "cs\" ) FILTER(false) #", 10, 0, null, null, null);
        assertThat(query).as("Lang injection leaked into query: " + query).doesNotContain("\" ) FILTER(false)");
    }

    @Test
    void buildConceptSearchQuery_withBothFilters_includesBothClauses() {
        List<String> iris = List.of("https://example.org/ontology/1");
        List<RelationType> types = List.of(RelationType.SUBCLASS);

        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery("test", "cs", 10, 0, iris, types, null);

        assertThat(query).contains("VALUES ?ontology");
        assertThat(query).contains("FILTER EXISTS");
    }

    @Test
    void buildConceptSearchQuery_appliesLanguagePreference() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery("test", "en", 10, 0, null, null, null);

        assertThat(query).contains("LANG(?prefLabel) = \"en\"");
    }

    @Test
    void buildConceptSearchQuery_projectsRoleMarkers() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery("test", "cs", 10, 0, null, null, null);

        assertThat(query).contains("?roleTrida");
        assertThat(query).contains("?roleVlastnost");
        assertThat(query).contains("?roleVztah");
    }

    @Test
    void buildConceptSearchQuery_roleMarkersAcceptOfnAndOwlTypes() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery("test", "cs", 10, 0, null, null, null);

        // OFN role tags (ISMD-published) and OWL types (NKD-carried) both bind the marker.
        assertThat(query).contains("http://www.w3.org/2002/07/owl#Class");
        assertThat(query).contains("http://www.w3.org/2002/07/owl#DatatypeProperty");
        assertThat(query).contains("http://www.w3.org/2002/07/owl#ObjectProperty");
        assertThat(query).contains("třída");
        assertThat(query).contains("vlastnost");
        assertThat(query).contains("vztah");
    }

    @Test
    void buildConceptSearchQuery_includesOntologyVariable() {
        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery("test", "cs", 10, 0, null, null, null);

        assertThat(query).contains("?ontology");
        assertThat(query).contains("skos:inScheme ?ontology");
    }

    @Test
    void isSafeHttpIri_acceptsHttpAndHttps() {
        assertThat(SparqlIriValidator.isSafeHttpIri("https://slovnik.gov.cz/datovy/osoby")).isTrue();
        assertThat(SparqlIriValidator.isSafeHttpIri("http://example.org/ontology#Thing")).isTrue();
    }

    @Test
    void isSafeHttpIri_rejectsInjectionAttempts() {
        // Space + SPARQL fragment that would break out of <...>
        assertThat(SparqlIriValidator.isSafeHttpIri(
                "http://example.org/ont } SELECT * WHERE { ?s ?p ?o"))
                .isFalse();
        assertThat(SparqlIriValidator.isSafeHttpIri("http://example.org/>malicious")).isFalse();
        assertThat(SparqlIriValidator.isSafeHttpIri("http://example.org/a\nb")).isFalse();
    }

    @Test
    void isSafeHttpIri_rejectsNonHttpSchemes() {
        assertThat(SparqlIriValidator.isSafeHttpIri("file:///etc/passwd")).isFalse();
        assertThat(SparqlIriValidator.isSafeHttpIri("javascript:alert(1)")).isFalse();
        assertThat(SparqlIriValidator.isSafeHttpIri("urn:isbn:0451450523")).isFalse();
    }

    @Test
    void isSafeHttpIri_rejectsNullAndBlank() {
        assertThat(SparqlIriValidator.isSafeHttpIri(null)).isFalse();
        assertThat(SparqlIriValidator.isSafeHttpIri("")).isFalse();
        assertThat(SparqlIriValidator.isSafeHttpIri("   ")).isFalse();
    }

    @Test
    void buildConceptSearchQuery_dropsMaliciousOntologyIri() {
        List<String> iris = List.of(
                "https://slovnik.gov.cz/datovy/osoby",
                "http://example.org/ont } SELECT * WHERE { ?s ?p ?o"
        );

        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery("osoba", "cs", 20, 0, iris, null, null);

        assertThat(query).contains("<https://slovnik.gov.cz/datovy/osoby>");
        assertThat(query).doesNotContain("SELECT * WHERE { ?s ?p ?o");
        assertThat(query).doesNotContain("} SELECT");
    }

    @Test
    void buildConceptSearchQuery_allInvalidOntologyIris_noValuesClause() {
        List<String> iris = List.of(
                "javascript:alert(1)",
                "http://example.org/bad }"
        );

        String query = NKDSPARQLSearchQuery.buildConceptSearchQuery("osoba", "cs", 20, 0, iris, null, null);

        assertThat(query).doesNotContain("VALUES ?ontology");
    }
}
