package com.dia.ismdtoolbackend.service.nkod;

import com.dia.ismdtoolbackend.client.NkodSparqlClient;
import com.dia.ismdtoolbackend.config.NkodConfig;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.models.nkod.NkodCodelist;
import com.dia.ismdtoolbackend.models.nkod.NkodCodelistEntry;
import com.dia.ismdtoolbackend.utility.codelist.CodelistDistributionReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NkodCodelistSnapshotHolderTest {

    private static final String POHLAVI = "https://data.gov.cz/zdroj/datové-sady/17651921/5ccc4289";
    private static final String ISVAV = "https://data.gov.cz/zdroj/datové-sady/00216208/102d4d07";
    private static final String ROLE = "https://data.gov.cz/zdroj/datové-sady/72050365/1072138515";

    private static final String POHLAVI_URL = "https://rpp/ciselnik_151_20250101.jsonld";
    private static final String POHLAVI_URL_NEXT = "https://rpp/ciselnik_151_20260101.jsonld";
    private static final String POHLAVI_IRI = "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/číselníky/151/2025-01-01";
    private static final String ISVAV_URL = "https://mff/klasifikace-oborů-isvav.jsonld";
    private static final String ISVAV_IRI = "https://data.mff.cuni.cz/zdroj/číselníky/klasifikace-výzkumných-oborů";

    @Mock
    private NkodSparqlClient client;
    @Mock
    private CodelistDistributionReader reader;

    private NkodConfig config;
    private MutableClock clock;
    private final List<NkodCodelistSnapshotHolder> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        config = new NkodConfig();
        clock = new MutableClock(Instant.parse("2026-09-30T10:00:00Z"));
        when(client.isEndpointConfigured()).thenReturn(true);
        when(reader.readCodeListIri(anyString())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        created.forEach(NkodCodelistSnapshotHolder::shutdown);
    }

    private NkodCodelistSnapshotHolder holder() {
        NkodCodelistSnapshotHolder holder = new NkodCodelistSnapshotHolder(client, reader, clock, config);
        created.add(holder);
        return holder;
    }

    private static NkodCodelistEntry entry(String datasetIri, String title, String... urls) {
        return new NkodCodelistEntry(
                NkodCodelist.builder().datasetIri(datasetIri).title(title).publisher("DIA").build(),
                List.of(urls));
    }

    private static List<NkodCodelistEntry> catalogue() {
        return List.of(entry(POHLAVI, "Pohlaví", POHLAVI_URL),
                entry(ISVAV, "Klasifikace oborů ISVaV", ISVAV_URL),
                entry(ROLE, "Role účastníka v projektu"));
    }

    private static String iriOf(NkodCodelistSnapshotHolder holder, String datasetIri) {
        return holder.peek().find(datasetIri).orElseThrow().codeListIri();
    }

    @Test
    void warmOnStartup_resolvesEveryEntryAndKeepsUnresolvedOnes() {
        when(client.fetchCodelists()).thenReturn(catalogue());
        when(reader.readCodeListIri(POHLAVI_URL)).thenReturn(Optional.of(POHLAVI_IRI));
        when(reader.readCodeListIri(ISVAV_URL)).thenReturn(Optional.of(ISVAV_IRI));

        NkodCodelistSnapshotHolder holder = holder();
        holder.warmOnStartup();

        assertThat(holder.peek().size()).isEqualTo(3);
        assertThat(iriOf(holder, POHLAVI)).isEqualTo(POHLAVI_IRI);
        assertThat(iriOf(holder, ISVAV)).isEqualTo(ISVAV_IRI);
        assertThat(holder.peek().find(ROLE)).get().matches(e -> !e.isResolved(), "unresolved but kept");
        assertThat(holder.peek().getEntries()).extracting(NkodCodelistEntry::datasetIri)
                .containsExactly(POHLAVI, ISVAV, ROLE);
    }

    @Test
    void warmOnStartup_skipsWhenEndpointNotConfigured() {
        when(client.isEndpointConfigured()).thenReturn(false);

        NkodCodelistSnapshotHolder holder = holder();
        holder.warmOnStartup();

        verify(client, never()).fetchCodelists();
        assertThat(holder.peek().isLoaded()).isFalse();
    }

    @Test
    void warmOnStartup_swallowsFailure() {
        when(client.fetchCodelists()).thenThrow(new SparqlEndpointUnavailableException("NKOD", "down"));

        NkodCodelistSnapshotHolder holder = holder();
        holder.warmOnStartup();

        assertThat(holder.peek().isLoaded()).isFalse();
    }

    @Test
    void secondUrlIsTriedWhenTheFirstYieldsNothing() {
        String first = "https://mpsv/formy-soc-sluzby-ofn.jsonld";
        String second = "https://mpsv/formy-soc-sluzby.jsonld";
        when(client.fetchCodelists()).thenReturn(List.of(entry(POHLAVI, "Formy", first, second)));
        when(reader.readCodeListIri(second)).thenReturn(Optional.of("https://mpsv/ciselnik/formy"));

        NkodCodelistSnapshotHolder holder = holder();
        holder.scheduledRefresh();

        assertThat(iriOf(holder, POHLAVI)).isEqualTo("https://mpsv/ciselnik/formy");
    }

    @Test
    void aReaderThatThrows_leavesTheEntryUnresolvedButTheRefreshSucceeds() {
        when(client.fetchCodelists()).thenReturn(catalogue());
        when(reader.readCodeListIri(POHLAVI_URL)).thenThrow(new IllegalStateException("boom"));
        when(reader.readCodeListIri(ISVAV_URL)).thenReturn(Optional.of(ISVAV_IRI));

        NkodCodelistSnapshotHolder holder = holder();
        holder.scheduledRefresh();

        assertThat(holder.peek().find(POHLAVI)).get().matches(e -> !e.isResolved());
        assertThat(iriOf(holder, ISVAV)).isEqualTo(ISVAV_IRI);
    }

    @Test
    void failedRead_carriesThePreviousIriOver_whenUrlsAreUnchanged() {
        when(client.fetchCodelists()).thenReturn(catalogue());
        when(reader.readCodeListIri(POHLAVI_URL)).thenReturn(Optional.of(POHLAVI_IRI));
        NkodCodelistSnapshotHolder holder = holder();
        holder.scheduledRefresh();

        when(reader.readCodeListIri(POHLAVI_URL)).thenReturn(Optional.empty());
        holder.scheduledRefresh();

        assertThat(iriOf(holder, POHLAVI)).isEqualTo(POHLAVI_IRI);
    }

    @Test
    void failedRead_doesNotCarryOver_whenUrlsChanged() {
        when(client.fetchCodelists()).thenReturn(catalogue());
        when(reader.readCodeListIri(POHLAVI_URL)).thenReturn(Optional.of(POHLAVI_IRI));
        NkodCodelistSnapshotHolder holder = holder();
        holder.scheduledRefresh();

        when(client.fetchCodelists()).thenReturn(List.of(entry(POHLAVI, "Pohlaví", POHLAVI_URL_NEXT)));
        holder.scheduledRefresh();

        assertThat(holder.peek().find(POHLAVI)).get().matches(e -> !e.isResolved(),
                "a new version with a broken file must not inherit the old version's IRI");
    }

    @Test
    void successfulRead_replacesThePreviousIri() {
        when(client.fetchCodelists()).thenReturn(catalogue());
        when(reader.readCodeListIri(POHLAVI_URL)).thenReturn(Optional.of(POHLAVI_IRI));
        NkodCodelistSnapshotHolder holder = holder();
        holder.scheduledRefresh();

        when(reader.readCodeListIri(POHLAVI_URL)).thenReturn(Optional.of("https://rpp/číselníky/151/2026-01-01"));
        holder.scheduledRefresh();

        assertThat(iriOf(holder, POHLAVI)).isEqualTo("https://rpp/číselníky/151/2026-01-01");
    }

    @Test
    void datasetGoneFromTheCatalogue_isGoneFromTheSnapshot() {
        when(client.fetchCodelists()).thenReturn(catalogue());
        NkodCodelistSnapshotHolder holder = holder();
        holder.scheduledRefresh();

        when(client.fetchCodelists()).thenReturn(List.of(entry(ISVAV, "Klasifikace oborů ISVaV", ISVAV_URL)));
        holder.scheduledRefresh();

        assertThat(holder.findByDatasetIri(POHLAVI)).isEmpty();
        assertThat(holder.findByDatasetIri(ISVAV)).isPresent();
    }

    @Test
    void stalledRead_isCancelledAtTheBudget_andTheRefreshCompletes() {
        config.getCodelist().setDistributionTimeoutMs(100);
        config.getCodelist().setDistributionConcurrency(1);
        when(client.fetchCodelists()).thenReturn(List.of(entry(POHLAVI, "Pohlaví", POHLAVI_URL)));
        when(reader.readCodeListIri(POHLAVI_URL)).thenAnswer(inv -> {
            Thread.sleep(10_000);
            return Optional.of(POHLAVI_IRI);
        });

        NkodCodelistSnapshotHolder holder = holder();
        long start = System.nanoTime();
        holder.scheduledRefresh();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        assertThat(holder.peek().find(POHLAVI)).get().matches(e -> !e.isResolved());
    }

    @Test
    void findByDatasetIri_beforeTheFirstLoad_neverFetches() {
        NkodCodelistSnapshotHolder holder = holder();

        assertThat(holder.findByDatasetIri(POHLAVI)).isEmpty();

        verifyNoInteractions(client);
        verifyNoInteractions(reader);
    }

    @Test
    void findByDatasetIri_matchesAPercentEncodedIri() {
        when(client.fetchCodelists()).thenReturn(catalogue());
        NkodCodelistSnapshotHolder holder = holder();
        holder.scheduledRefresh();

        assertThat(holder.findByDatasetIri(POHLAVI.replace("datové-sady", "datov%C3%A9-sady"))).isPresent();
    }

    @Test
    void get_freshSnapshot_doesNotRefetch() {
        when(client.fetchCodelists()).thenReturn(catalogue());
        NkodCodelistSnapshotHolder holder = holder();

        holder.get();
        holder.get();

        verify(client, times(1)).fetchCodelists();
    }

    @Test
    void get_staleSnapshot_refetches() {
        when(client.fetchCodelists()).thenReturn(catalogue());
        NkodCodelistSnapshotHolder holder = holder();
        holder.get();

        clock.advance(Duration.ofHours(config.getCodelist().getTtlHours() + 1));
        holder.get();

        verify(client, times(2)).fetchCodelists();
    }

    @Test
    void get_refreshFailure_servesStaleSnapshot() {
        when(client.fetchCodelists()).thenReturn(catalogue());
        NkodCodelistSnapshotHolder holder = holder();
        holder.get();

        clock.advance(Duration.ofHours(config.getCodelist().getTtlHours() + 1));
        when(client.fetchCodelists()).thenThrow(new SparqlEndpointUnavailableException("NKOD", "down"));

        assertThat(holder.get().size()).isEqualTo(3);
    }

    @Test
    void get_refreshFailureWithNothingLoaded_throws() {
        when(client.fetchCodelists()).thenThrow(new SparqlEndpointUnavailableException("NKOD", "down"));

        assertThatThrownBy(() -> holder().get()).isInstanceOf(SparqlEndpointUnavailableException.class);
    }

    @Test
    void scheduledRefreshFailure_keepsTheSnapshot() {
        when(client.fetchCodelists()).thenReturn(catalogue());
        NkodCodelistSnapshotHolder holder = holder();
        holder.scheduledRefresh();

        when(client.fetchCodelists()).thenThrow(new SparqlEndpointUnavailableException("NKOD", "down"));
        holder.scheduledRefresh();

        assertThat(holder.peek().size()).isEqualTo(3);
    }

    @Test
    void emptyCatalogueResult_replacesTheSnapshot() {
        when(client.fetchCodelists()).thenReturn(catalogue());
        NkodCodelistSnapshotHolder holder = holder();
        holder.scheduledRefresh();

        when(client.fetchCodelists()).thenReturn(List.of());
        holder.scheduledRefresh();

        assertThat(holder.peek().isLoaded()).isTrue();
        assertThat(holder.peek().size()).isZero();
        assertThat(holder.findByDatasetIri(POHLAVI)).isEmpty();
    }

    @Test
    void get_loadedSnapshotWithNoDatasets_isFreshAndDoesNotRefetch() {
        when(client.fetchCodelists()).thenReturn(List.of());
        NkodCodelistSnapshotHolder holder = holder();

        holder.get();
        holder.get();

        verify(client, times(1)).fetchCodelists();
    }

    @Test
    void get_refreshFailure_servesALoadedSnapshotWithNoDatasets() {
        when(client.fetchCodelists()).thenReturn(List.of());
        NkodCodelistSnapshotHolder holder = holder();
        holder.get();

        clock.advance(Duration.ofHours(config.getCodelist().getTtlHours() + 1));
        when(client.fetchCodelists()).thenThrow(new SparqlEndpointUnavailableException("NKOD", "down"));

        assertThat(holder.get().isLoaded()).isTrue();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
