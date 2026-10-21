package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.config.NkodConfig;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.models.nkod.NkodDatasetDetail;
import com.dia.ismdtoolbackend.models.nkod.NkodDatasetRow;
import com.dia.ismdtoolbackend.models.nkod.NkodDistribution;
import com.dia.ismdtoolbackend.testsupport.sparql.SparqlJson;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.matching.MatchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.dia.ismdtoolbackend.testsupport.sparql.SparqlJson.literal;
import static com.dia.ismdtoolbackend.testsupport.sparql.SparqlJson.uri;
import static com.dia.ismdtoolbackend.testsupport.sparql.SparqlStubs.serverError;
import static com.dia.ismdtoolbackend.testsupport.sparql.SparqlStubs.sparqlJson;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.okForContentType;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The NKOD client over real HTTP against WireMock: which queries leave, and how answers map. */
class NkodSparqlClientHttpTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String DATASET = "https://data.gov.cz/zdroj/datové-sady/00007064/123";
    private static final String DATASET_PERCENT_ENCODED = "https://data.gov.cz/zdroj/datov%C3%A9-sady/00007064/123";
    private static final String CONCEPT = "https://slovník.gov.cz/agendový/104/pojem/adresa";

    @RegisterExtension
    static final WireMockExtension WM = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private NkodSparqlClient client;

    @BeforeEach
    void setUp() {
        NkodConfig config = new NkodConfig();
        config.getSparql().setEndpoint(WM.baseUrl() + "/sparql");
        config.getSparql().setTimeout(2000);
        client = new NkodSparqlClient(config, HTTP);
    }

    /** The SPARQL text of a request, whether Jena sent it as a GET parameter or a form body. */
    private static String sparqlOf(Request request) {
        String raw = request.getMethod().getName().equals("GET")
                ? URI.create(request.getAbsoluteUrl()).getRawQuery()
                : request.getBodyAsString();
        for (String pair : raw == null ? new String[0] : raw.split("&")) {
            if (pair.startsWith("query=")) {
                return URLDecoder.decode(pair.substring("query=".length()), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    /** The most recently added matching stub wins, so add generic markers before specific ones. */
    private static void answer(String marker, com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response) {
        WM.stubFor(any(anyUrl())
                .andMatching(request -> MatchResult.of(sparqlOf(request).contains(marker)))
                .willReturn(response));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder ask(boolean value) {
        return okForContentType("application/sparql-results+json", "{\"head\":{},\"boolean\":" + value + "}");
    }

    private static List<String> sentQueries() {
        return WM.findAll(anyRequestedFor(anyUrl())).stream().map(r -> sparqlOf((Request) r)).toList();
    }

    @Test
    void detailCombinesTitlesConceptsAndDistributions() {
        answer("ASK", ask(true));
        answer("SELECT", sparqlJson(SparqlJson.select("nazev", "nazevLang", "popis", "popisLang", "pojem")
                .row(literal("nazev", "Adresní místa"), literal("nazevLang", "cs"),
                        literal("popis", "Seznam adres"), uri("pojem", CONCEPT))
                .row(literal("nazev", "Address points"), literal("nazevLang", "en"), uri("pojem", CONCEPT))
                .row(literal("nazev", "Bez jazyka"))));
        answer("dcat:distribution", sparqlJson(SparqlJson
                .select("dist", "nazev", "nazevLang", "pristupoveUrl", "stahovaciUrl", "format", "mediaTyp", "sluzba")
                .row(uri("dist", DATASET + "/d1"), literal("nazev", "CSV"), literal("nazevLang", "cs"),
                        uri("stahovaciUrl", "https://example.test/data.csv"),
                        uri("format", "http://publications.europa.eu/resource/authority/file-type/CSV"))
                .row(uri("dist", DATASET + "/d1"), literal("nazev", "CSV file"), literal("nazevLang", "en"),
                        uri("stahovaciUrl", "https://example.test/data.csv"))
                .row(uri("dist", DATASET + "/api"), uri("pristupoveUrl", "https://example.test/api"),
                        uri("sluzba", "https://example.test/api/service"))));

        NkodDatasetDetail detail = client.fetchDatasetDetail(DATASET).orElseThrow();

        assertThat(detail.iri()).isEqualTo(DATASET);
        assertThat(detail.name()).containsExactly(Map.entry("cs", "Adresní místa"), Map.entry("en", "Address points"));
        assertThat(detail.description()).containsExactly(Map.entry("cs", "Seznam adres"));
        assertThat(detail.conceptIris()).containsExactly(CONCEPT);
        assertThat(detail.distributions()).extracting(NkodDistribution::iri).containsExactly(DATASET + "/d1", DATASET + "/api");
        NkodDistribution file = detail.distributions().get(0);
        assertThat(file.name()).containsExactly(Map.entry("cs", "CSV"), Map.entry("en", "CSV file"));
        assertThat(file.link()).isEqualTo("https://example.test/data.csv");
        assertThat(file.isService()).isFalse();
        NkodDistribution api = detail.distributions().get(1);
        assertThat(api.link()).isEqualTo("https://example.test/api");
        assertThat(api.isService()).isTrue();
    }

    @Test
    void datasetAbsentFromTheCatalogueIsEmptyAfterOneAsk() {
        answer("ASK", ask(false));

        assertThat(client.fetchDatasetDetail(DATASET)).isEmpty();
        assertThat(sentQueries()).singleElement().asString().contains("ASK");
    }

    @Test
    void failingDistributionsStillReturnTheDetail() {
        answer("ASK", ask(true));
        answer("SELECT", sparqlJson(SparqlJson.select("nazev", "nazevLang", "pojem")
                .row(literal("nazev", "Adresní místa"), literal("nazevLang", "cs"))));
        answer("dcat:distribution", serverError());

        Optional<NkodDatasetDetail> detail = client.fetchDatasetDetail(DATASET);

        assertThat(detail).hasValueSatisfying(d -> {
            assertThat(d.name()).containsEntry("cs", "Adresní místa");
            assertThat(d.distributions()).isEmpty();
        });
    }

    @Test
    void failingExistenceCheckIsUnavailable() {
        answer("ASK", serverError());

        assertThatThrownBy(() -> client.fetchDatasetDetail(DATASET))
                .isInstanceOf(SparqlEndpointUnavailableException.class);
    }

    @Test
    void percentEncodedIriIsQueriedAsRawUtf8() {
        answer("ASK", ask(false));

        client.fetchDatasetDetail(DATASET_PERCENT_ENCODED);

        assertThat(sentQueries()).singleElement().asString()
                .contains("<" + DATASET + ">")
                .doesNotContain("%C3%A9");
    }

    @Test
    void datasetsOfAConceptCollapseOneRowPerLanguage() {
        answer("SELECT", sparqlJson(SparqlJson.select("ds", "nazev", "nazevLang", "popis", "popisLang")
                .row(uri("ds", DATASET), literal("nazev", "Adresní místa"), literal("nazevLang", "cs"))
                .row(uri("ds", DATASET), literal("nazev", "Address points"), literal("nazevLang", "en"),
                        literal("popis", " "))
                .row(literal("nazev", "row without dataset"))));

        List<NkodDatasetRow> rows = client.fetchDatasetsByConcept(CONCEPT);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.iri()).isEqualTo(DATASET);
            assertThat(row.name()).containsOnlyKeys("cs", "en");
            assertThat(row.description()).isEmpty();
        });
        assertThat(sentQueries()).singleElement().asString().contains("<" + CONCEPT + ">");
    }

    @Test
    void unsafeConceptIriIsNeverSent() {
        assertThat(client.fetchDatasetsByConcept("not an <iri>")).isEmpty();
        assertThat(sentQueries()).isEmpty();
    }
}
