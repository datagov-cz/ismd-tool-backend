package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.config.NkodConfig;
import org.junit.jupiter.api.Test;

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
}