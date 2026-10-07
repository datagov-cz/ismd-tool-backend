package com.dia.ismdtoolbackend.query;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.sparql.core.Var;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NKODSPARQLCodelistQueryTest {

    private static int occurrences(String haystack, String needle) {
        return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    @Test
    void listQuery_parsesAndProjectsTheMapperColumns() {
        Query q = QueryFactory.create(NKODSPARQLCodelistQuery.buildListQuery());

        assertThat(q.getProjectVars()).extracting(Var::getVarName).containsExactly(
                "dataset", "title", "downloadUrl", "publisher", "description", "rppIdentifier", "validFrom");
        assertThat(q.getGroupBy().getVars()).extracting(Var::getVarName)
                .containsExactly("dataset", "title", "downloadUrl");
        assertThat(q.getOrderBy()).hasSize(1);
    }

    @Test
    void listQuery_coversSeriesAndStandaloneDatasets() {
        String q = NKODSPARQLCodelistQuery.buildListQuery();

        assertThat(q).contains("?dataset a dcat:DatasetSeries");
        assertThat(q).contains("?dataset a dcat:Dataset ;");
        assertThat(q).contains("UNION");
    }

    @Test
    void listQuery_standaloneBranchExcludesSeriesAndSeriesMembers() {
        String q = NKODSPARQLCodelistQuery.buildListQuery();

        assertThat(q).contains("FILTER NOT EXISTS { ?dataset a dcat:DatasetSeries }");
        assertThat(q).contains("FILTER NOT EXISTS { ?dataset dcat:inSeries ?anySeries }");
    }

    @Test
    void listQuery_conformsToIsOnlyAnExistenceCheck() {
        String q = NKODSPARQLCodelistQuery.buildListQuery();

        assertThat(occurrences(q, "dcterms:conformsTo")).isEqualTo(2);
        assertThat(occurrences(q, "FILTER EXISTS { ?dataset dcterms:conformsTo ?c .")).isEqualTo(2);
        assertThat(q).contains("STRSTARTS(STR(?c), \"https://ofn.gov.cz/číselníky\")");
    }

    @Test
    void listQuery_filtersTitleAndDescriptionToCzech() {
        String q = NKODSPARQLCodelistQuery.buildListQuery();

        assertThat(occurrences(q, "FILTER(LANG(?title) = \"cs\")")).isEqualTo(2);
        assertThat(q).contains("FILTER(LANG(?desc) = \"cs\")");
    }

    @Test
    void listQuery_projectsPublisherIriNotName() {
        String q = NKODSPARQLCodelistQuery.buildListQuery();

        assertThat(q).contains("OPTIONAL { ?dataset dcterms:publisher ?pub }");
        assertThat(q).doesNotContain("foaf:name");
    }

    @Test
    void listQuery_seriesUsesOnlyTheOpenEndedVersion() {
        String q = NKODSPARQLCodelistQuery.buildListQuery();

        assertThat(q).contains("FILTER NOT EXISTS { ?ds dcterms:temporal ?t . ?t dcat:endDate ?ed . }");
        assertThat(occurrences(q, "FILTER(STRENDS(STR(?downloadUrl), \".jsonld\"))")).isEqualTo(2);
    }

    @Test
    void publisherNamesQuery_parsesAndBindsEveryPublisherOnce() {
        String a = "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/orgán-veřejné-moci/17651921";
        String b = "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/orgán-veřejné-moci/00216208";

        String q = NKODSPARQLCodelistQuery.buildPublisherNamesQuery(List.of(a, b, a));

        Query parsed = QueryFactory.create(q);
        assertThat(parsed.getProjectVars()).extracting(Var::getVarName).containsExactly("publisher", "name");
        assertThat(occurrences(q, "<" + a + ">")).isEqualTo(1);
        assertThat(occurrences(q, "<" + b + ">")).isEqualTo(1);
    }

    @Test
    void publisherNamesQuery_skipsUnsafeIris() {
        String safe = "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/orgán-veřejné-moci/17651921";
        String injection = "https://evil.example/x> } ; DROP ALL ; SELECT * { <a";

        String q = NKODSPARQLCodelistQuery.buildPublisherNamesQuery(
                new LinkedHashSet<>(Arrays.asList(safe, injection, "not-an-iri", null)));

        assertThat(q).contains("<" + safe + ">");
        assertThat(q).doesNotContain("evil.example").doesNotContain("not-an-iri");
    }

    @Test
    void publisherNamesQuery_returnsNullWhenNothingToLookUp() {
        assertThat(NKODSPARQLCodelistQuery.buildPublisherNamesQuery(null)).isNull();
        assertThat(NKODSPARQLCodelistQuery.buildPublisherNamesQuery(List.of())).isNull();
        assertThat(NKODSPARQLCodelistQuery.buildPublisherNamesQuery(List.of("javascript:alert(1)"))).isNull();
    }
}
