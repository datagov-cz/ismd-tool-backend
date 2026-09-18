package com.dia.ismdtoolbackend.service.nkod;

import com.dia.ismdtoolbackend.client.NkodSparqlClient;
import com.dia.ismdtoolbackend.config.NkodConfig;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.models.nkod.NkodDatasetRow;
import com.dia.ismdtoolbackend.models.nkod.NkodDatasetSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NkodDatasetSnapshotHolderTest {

    @Mock
    private NkodSparqlClient client;

    private NkodConfig config;

    @BeforeEach
    void setUp() {
        config = new NkodConfig();
    }

    private NkodDatasetSnapshotHolder holder(Clock clock) {
        return new NkodDatasetSnapshotHolder(client, clock, config);
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-09-15T10:00:00Z"), ZoneOffset.UTC);
    }

    private static NkodDatasetRow row(String iri, String title) {
        return new NkodDatasetRow(iri, Map.of("cs", title), Map.of());
    }

    /**
     * Baseline: a configured endpoint must actually harvest. Pins the direction of the
     * endpoint-configured check — an inverted check still compiles and still passes the
     * "skips when unconfigured" test below, so without this pair the feature could silently
     * never warm.
     */
    @Test
    void warmOnStartupHarvestsWhenEndpointConfigured() {
        when(client.isEndpointConfigured()).thenReturn(true);
        when(client.harvestDatasets()).thenReturn(List.of(row("urn:a", "Alfa")));

        NkodDatasetSnapshotHolder holder = holder(fixedClock());
        holder.warmOnStartup();

        verify(client, times(1)).harvestDatasets();
        assertThat(holder.peek().size()).isEqualTo(1);
    }

    @Test
    void warmOnStartupSkipsWhenEndpointNotConfigured() {
        when(client.isEndpointConfigured()).thenReturn(false);

        NkodDatasetSnapshotHolder holder = holder(fixedClock());
        holder.warmOnStartup();

        verify(client, never()).harvestDatasets();
        assertThat(holder.peek().isEmpty()).isTrue();
    }

    /** A failed warm must not prevent startup; the lazy path retries later. */
    @Test
    void warmOnStartupSwallowsEndpointFailure() {
        when(client.isEndpointConfigured()).thenReturn(true);
        when(client.harvestDatasets())
                .thenThrow(new SparqlEndpointUnavailableException("NKOD", "down"));

        NkodDatasetSnapshotHolder holder = holder(fixedClock());

        holder.warmOnStartup();

        assertThat(holder.peek().isEmpty()).isTrue();
    }

    /**
     * A flaky endpoint must never evict good data. Starts from a genuinely warm snapshot so
     * the degraded path is not also the fixture default — otherwise this passes even if
     * serve-stale is removed entirely.
     */
    @Test
    void servesStaleSnapshotWhenRefreshFails() {
        when(client.isEndpointConfigured()).thenReturn(true);
        when(client.harvestDatasets()).thenReturn(List.of(row("urn:a", "Alfa")));

        MutableClock clock = new MutableClock(Instant.parse("2026-09-15T10:00:00Z"));
        NkodDatasetSnapshotHolder holder = holder(clock);
        holder.warmOnStartup();
        assertThat(holder.peek().size()).isEqualTo(1);

        when(client.harvestDatasets())
                .thenThrow(new SparqlEndpointUnavailableException("NKOD", "down"));
        clock.advanceHours(config.getSnapshot().getTtlHours() + 1);

        NkodDatasetSnapshot served = holder.get();

        assertThat(served.size()).isEqualTo(1);
    }

    /** With nothing cached there is nothing to serve, so the failure must surface. */
    @Test
    void propagatesFailureWhenNoSnapshotCached() {
        when(client.harvestDatasets())
                .thenThrow(new SparqlEndpointUnavailableException("NKOD", "down"));

        NkodDatasetSnapshotHolder holder = holder(fixedClock());

        assertThatThrownBy(holder::get)
                .isInstanceOf(SparqlEndpointUnavailableException.class);
    }

    /** A fresh snapshot must be reused rather than re-harvested on every call. */
    @Test
    void reusesFreshSnapshotWithoutRefetching() {
        when(client.isEndpointConfigured()).thenReturn(true);
        when(client.harvestDatasets()).thenReturn(List.of(row("urn:a", "Alfa")));

        NkodDatasetSnapshotHolder holder = holder(fixedClock());
        holder.warmOnStartup();
        holder.get();
        holder.get();

        verify(client, times(1)).harvestDatasets();
    }

    @Test
    void rebuildsOnceSnapshotIsStale() {
        when(client.isEndpointConfigured()).thenReturn(true);
        when(client.harvestDatasets()).thenReturn(List.of(row("urn:a", "Alfa")));

        MutableClock clock = new MutableClock(Instant.parse("2026-09-15T10:00:00Z"));
        NkodDatasetSnapshotHolder holder = holder(clock);
        holder.warmOnStartup();

        clock.advanceHours(config.getSnapshot().getTtlHours() + 1);
        when(client.harvestDatasets())
                .thenReturn(List.of(row("urn:a", "Alfa"), row("urn:b", "Beta")));

        assertThat(holder.get().size()).isEqualTo(2);
        verify(client, times(2)).harvestDatasets();
    }

    /** Test clock that can be moved past the TTL. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advanceHours(long hours) {
            now = now.plusSeconds(hours * 3600);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}