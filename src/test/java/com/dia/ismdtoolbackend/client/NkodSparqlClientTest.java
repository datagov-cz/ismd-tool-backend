package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.config.NkodConfig;
import com.dia.ismdtoolbackend.models.nkod.NkodDatasetRow;
import com.dia.ismdtoolbackend.models.nkod.NkodDistribution;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dia.ismdtoolbackend.testsupport.sparql.SparqlJson;
import org.apache.jena.query.ResultSet;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static com.dia.ismdtoolbackend.testsupport.sparql.SparqlJson.literal;
import static com.dia.ismdtoolbackend.testsupport.sparql.SparqlJson.uri;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real client rather than a mock.
 *
 * <p>{@code NkodDatasetSnapshotHolderTest} stubs {@code isEndpointConfigured()}, so an
 * inverted implementation there is invisible — the stub answers instead of the method. These
 * tests call it for real, which is the only place that inversion shows up.
 */
class NkodSparqlClientTest {

    private static NkodSparqlClient clientWithEndpoint(String endpoint) {
        NkodConfig config = new NkodConfig();
        config.getSparql().setEndpoint(endpoint);
        return new NkodSparqlClient(config, null);
    }

    @Test
    void reportsConfiguredWhenEndpointIsSet() {
        assertThat(clientWithEndpoint("https://data.gov.cz/sparql").isEndpointConfigured())
                .isTrue();
    }

    @Test
    void warnsAtStartupOnlyWhenTheEndpointIsMissing() {
        Logger logger = (Logger) LoggerFactory.getLogger(NkodSparqlClient.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            clientWithEndpoint("https://data.gov.cz/sparql").warnIfEndpointMissing();
            assertThat(logs.list).isEmpty();

            clientWithEndpoint("").warnIfEndpointMissing();
            assertThat(logs.list).singleElement().satisfies(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                assertThat(e.getFormattedMessage()).contains("nkod.sparql.endpoint is not configured");
            });
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void reportsNotConfiguredWhenEndpointIsEmpty() {
        assertThat(clientWithEndpoint("").isEndpointConfigured()).isFalse();
    }

    @Test
    void reportsNotConfiguredWhenEndpointIsBlank() {
        assertThat(clientWithEndpoint("   ").isEndpointConfigured()).isFalse();
    }

    /**
     * An unsafe IRI must be rejected before it reaches the endpoint, and without a live call —
     * this client is built with no endpoint, so any round-trip attempt would throw instead.
     */
    @Test
    void rejectsUnsafeIriWithoutCallingEndpoint() {
        NkodSparqlClient client = clientWithEndpoint("");

        assertThat(client.fetchDatasetDetail("not a valid <iri>")).isEmpty();
    }

    /**
     * Builds a real Jena {@link ResultSet} from SPARQL JSON — the exact wire format the
     * endpoint returns — so the mapper is exercised rather than a hand-made stand-in.
     */
    private static List<NkodDistribution> mapDistributions(SparqlJson result) {
        return ReflectionTestUtils.invokeMethod(NkodSparqlClient.class, "mapDistributionRows", result.toResultSet());
    }

    private static SparqlJson distributions() {
        return SparqlJson.select("dist", "nazev", "nazevLang", "pristupoveUrl",
                "stahovaciUrl", "format", "mediaTyp", "sluzba");
    }

    /**
     * The core rule behind the single {@code odkaz} field. Both URLs are present on ~91% of
     * distributions and byte-identical on 98% of those, so an inverted preference is
     * invisible in almost all real data — it only shows up on the 681 that genuinely differ.
     * This pins the direction explicitly.
     */
    @Test
    void prefersDownloadUrlWhenBothPresentAndDiffer() {
        List<NkodDistribution> result = mapDistributions(distributions().row(
                uri("dist", "urn:d1"),
                uri("pristupoveUrl", "https://host/page"),
                uri("stahovaciUrl", "https://host/data.csv")));

        assertThat(result).singleElement()
                .extracting(NkodDistribution::link)
                .isEqualTo("https://host/data.csv");
    }

    /** Every distribution has an accessURL; only ~91% have a downloadURL. */
    @Test
    void fallsBackToAccessUrlWhenDownloadUrlAbsent() {
        List<NkodDistribution> result = mapDistributions(distributions().row(
                uri("dist", "urn:d1"),
                uri("pristupoveUrl", "https://host/sparql")));

        assertThat(result).singleElement()
                .extracting(NkodDistribution::link)
                .isEqualTo("https://host/sparql");
    }

    /** No downloadURL means it is not a file, even without an explicit accessService. */
    @Test
    void marksDistributionWithoutDownloadUrlAsService() {
        List<NkodDistribution> result = mapDistributions(distributions().row(
                uri("dist", "urn:d1"),
                uri("pristupoveUrl", "https://host/wms?request=GetCapabilities")));

        assertThat(result).singleElement()
                .extracting(NkodDistribution::isService)
                .isEqualTo(true);
    }

    /** An explicit accessService flags a service even when a downloadURL is also offered. */
    @Test
    void marksDistributionWithAccessServiceAsService() {
        List<NkodDistribution> result = mapDistributions(distributions().row(
                uri("dist", "urn:d1"),
                uri("stahovaciUrl", "https://host/data.json"),
                uri("sluzba", "urn:service")));

        assertThat(result).singleElement()
                .extracting(NkodDistribution::isService)
                .isEqualTo(true);
    }

    /** A plain file distribution must NOT be flagged, or every row would read "Otevřít". */
    @Test
    void doesNotMarkPlainFileDistributionAsService() {
        List<NkodDistribution> result = mapDistributions(distributions().row(
                uri("dist", "urn:d1"),
                uri("pristupoveUrl", "https://host/data.csv"),
                uri("stahovaciUrl", "https://host/data.csv")));

        assertThat(result).singleElement()
                .extracting(NkodDistribution::isService)
                .isEqualTo(false);
    }

    /**
     * The catalogue emits one row per title language, so a two-language distribution arrives
     * as two rows. Collapsing them is what keeps a 3-distribution dataset from rendering 4
     * links — observed live on data.csu.gov.cz.
     */
    @Test
    void collapsesMultiLanguageTitleRowsIntoOneDistribution() {
        List<NkodDistribution> result = mapDistributions(distributions()
                .row(uri("dist", "urn:d1"), literal("nazev", "Rozhraní", "cs"), literal("nazevLang", "cs"),
                        uri("stahovaciUrl", "https://host/d"))
                .row(uri("dist", "urn:d1"), literal("nazev", "Interface", "en"), literal("nazevLang", "en"),
                        uri("stahovaciUrl", "https://host/d")));

        assertThat(result).singleElement()
                .extracting(NkodDistribution::name)
                .isEqualTo(Map.of("cs", "Rozhraní", "en", "Interface"));
    }

    /**
     * An unsafe concept IRI must be refused before any round-trip. This client has no endpoint,
     * so a call that slipped past the guard would throw rather than return empty.
     */
    @Test
    void rejectsUnsafeConceptIriWithoutCallingEndpoint() {
        NkodSparqlClient client = clientWithEndpoint("");

        assertThat(client.fetchDatasetsByConcept("https://x/a> } UNION { ?ds ?p ?o")).isEmpty();
        assertThat(client.fetchDatasetsByConcept("not an iri")).isEmpty();
        assertThat(client.fetchDatasetsByConcept(null)).isEmpty();
    }

    /**
     * Builds a real Jena {@link ResultSet} from the harvest/by-concept projection, so the
     * language-collapsing mapper is exercised on the actual wire format.
     */
    private static List<NkodDatasetRow> mapDatasetRows(SparqlJson result) {
        return ReflectionTestUtils.invokeMethod(clientWithEndpoint(""), "mapHarvestRows", result.toResultSet());
    }

    private static SparqlJson datasets() {
        return SparqlJson.select("ds", "nazev", "nazevLang", "popis", "popisLang");
    }

    /**
     * One dataset annotated in two languages arrives as two rows; collapsing them is what keeps
     * a single linked dataset from rendering twice on the concept page.
     */
    @Test
    void collapsesMultiLanguageDatasetRowsIntoOneRow() {
        List<NkodDatasetRow> result = mapDatasetRows(datasets()
                .row(uri("ds", "urn:ds1"), literal("nazev", "Adresy", "cs"), literal("nazevLang", "cs"))
                .row(uri("ds", "urn:ds1"), literal("nazev", "Addresses", "en"), literal("nazevLang", "en")));

        assertThat(result).singleElement().satisfies(row -> {
            assertThat(row.iri()).isEqualTo("urn:ds1");
            assertThat(row.name()).isEqualTo(Map.of("cs", "Adresy", "en", "Addresses"));
        });
    }

    /**
     * Title and description are OPTIONAL in the query, so an untitled dataset must still be
     * returned — dropping it would silently understate a concept's dataset count.
     */
    @Test
    void keepsDatasetWithNoTitle() {
        List<NkodDatasetRow> result = mapDatasetRows(datasets().row(uri("ds", "urn:ds1")));

        assertThat(result).singleElement().satisfies(row -> {
            assertThat(row.iri()).isEqualTo("urn:ds1");
            assertThat(row.name()).isEmpty();
        });
    }

    /** Several distinct datasets on one concept must stay distinct. */
    @Test
    void keepsDistinctDatasetsSeparate() {
        List<NkodDatasetRow> result = mapDatasetRows(datasets()
                .row(uri("ds", "urn:ds1"), literal("nazev", "Adresy", "cs"), literal("nazevLang", "cs"))
                .row(uri("ds", "urn:ds2"), literal("nazev", "Budovy", "cs"), literal("nazevLang", "cs")));

        assertThat(result).extracting(NkodDatasetRow::iri).containsExactly("urn:ds1", "urn:ds2");
    }
}