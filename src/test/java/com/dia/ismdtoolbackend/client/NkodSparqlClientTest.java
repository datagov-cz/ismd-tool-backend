package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.config.NkodConfig;
import com.dia.ismdtoolbackend.models.nkod.NkodDistribution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
    private static List<NkodDistribution> mapDistributions(String bindingsJson) {
        String json = """
                {"head":{"vars":["dist","nazev","nazevLang","pristupoveUrl",
                 "stahovaciUrl","format","mediaTyp","sluzba"]},
                 "results":{"bindings":[%s]}}
                """.formatted(bindingsJson);
        ResultSet rs = ResultSetFactory.fromJSON(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        return ReflectionTestUtils.invokeMethod(NkodSparqlClient.class, "mapDistributionRows", rs);
    }

    private static String uri(String var, String value) {
        return "\"%s\":{\"type\":\"uri\",\"value\":\"%s\"}".formatted(var, value);
    }

    /**
     * The core rule behind the single {@code odkaz} field. Both URLs are present on ~91% of
     * distributions and byte-identical on 98% of those, so an inverted preference is
     * invisible in almost all real data — it only shows up on the 681 that genuinely differ.
     * This pins the direction explicitly.
     */
    @Test
    void prefersDownloadUrlWhenBothPresentAndDiffer() {
        List<NkodDistribution> result = mapDistributions("{"
                + uri("dist", "urn:d1") + ","
                + uri("pristupoveUrl", "https://host/page") + ","
                + uri("stahovaciUrl", "https://host/data.csv") + "}");

        assertThat(result).singleElement()
                .extracting(NkodDistribution::link)
                .isEqualTo("https://host/data.csv");
    }

    /** Every distribution has an accessURL; only ~91% have a downloadURL. */
    @Test
    void fallsBackToAccessUrlWhenDownloadUrlAbsent() {
        List<NkodDistribution> result = mapDistributions("{"
                + uri("dist", "urn:d1") + ","
                + uri("pristupoveUrl", "https://host/sparql") + "}");

        assertThat(result).singleElement()
                .extracting(NkodDistribution::link)
                .isEqualTo("https://host/sparql");
    }

    /** No downloadURL means it is not a file, even without an explicit accessService. */
    @Test
    void marksDistributionWithoutDownloadUrlAsService() {
        List<NkodDistribution> result = mapDistributions("{"
                + uri("dist", "urn:d1") + ","
                + uri("pristupoveUrl", "https://host/wms?request=GetCapabilities") + "}");

        assertThat(result).singleElement()
                .extracting(NkodDistribution::isService)
                .isEqualTo(true);
    }

    /** An explicit accessService flags a service even when a downloadURL is also offered. */
    @Test
    void marksDistributionWithAccessServiceAsService() {
        List<NkodDistribution> result = mapDistributions("{"
                + uri("dist", "urn:d1") + ","
                + uri("stahovaciUrl", "https://host/data.json") + ","
                + uri("sluzba", "urn:service") + "}");

        assertThat(result).singleElement()
                .extracting(NkodDistribution::isService)
                .isEqualTo(true);
    }

    /** A plain file distribution must NOT be flagged, or every row would read "Otevřít". */
    @Test
    void doesNotMarkPlainFileDistributionAsService() {
        List<NkodDistribution> result = mapDistributions("{"
                + uri("dist", "urn:d1") + ","
                + uri("pristupoveUrl", "https://host/data.csv") + ","
                + uri("stahovaciUrl", "https://host/data.csv") + "}");

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
        List<NkodDistribution> result = mapDistributions(
                "{" + uri("dist", "urn:d1") + ","
                        + "\"nazev\":{\"type\":\"literal\",\"xml:lang\":\"cs\",\"value\":\"Rozhraní\"},"
                        + "\"nazevLang\":{\"type\":\"literal\",\"value\":\"cs\"},"
                        + uri("stahovaciUrl", "https://host/d") + "},"
                        + "{" + uri("dist", "urn:d1") + ","
                        + "\"nazev\":{\"type\":\"literal\",\"xml:lang\":\"en\",\"value\":\"Interface\"},"
                        + "\"nazevLang\":{\"type\":\"literal\",\"value\":\"en\"},"
                        + uri("stahovaciUrl", "https://host/d") + "}");

        assertThat(result).singleElement()
                .extracting(NkodDistribution::name)
                .isEqualTo(Map.of("cs", "Rozhraní", "en", "Interface"));
    }
}