package com.dia.ismdtoolbackend.service.rpp;

import com.dia.ismdtoolbackend.client.RppSparqlClient;
import com.dia.ismdtoolbackend.config.RppConfig;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.models.rpp.RppAgenda;
import com.dia.ismdtoolbackend.models.rpp.RppIsvs;
import com.dia.ismdtoolbackend.models.rpp.RppSnapshot;
import com.dia.ismdtoolbackend.testsupport.time.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RppSnapshotHolderTest {

    private static final Instant T0 = Instant.parse("2026-04-21T12:00:00Z");

    @Mock
    private RppSparqlClient client;

    private MutableClock clock;
    private RppSnapshotHolder holder;

    @BeforeEach
    void setUp() {
        clock = MutableClock.of(T0);
        RppConfig config = new RppConfig();
        config.getCache().setTtlHours(24);
        holder = new RppSnapshotHolder(client, clock, config);
    }

    @Test
    void coldFetchPopulatesCache() {
        when(client.fetchAllAgendas()).thenReturn(List.of(new RppAgenda("a-iri", "1", "Ag")));
        when(client.fetchAllIsvs()).thenReturn(List.of(new RppIsvs("i-iri", "10", "Is", List.of())));

        RppSnapshot snap = holder.get();

        assertThat(snap.getLoadedAt()).isEqualTo(T0);
        assertThat(snap.getAgendas()).hasSize(1);
        assertThat(snap.getIsvs()).hasSize(1);
        verify(client, times(1)).fetchAllAgendas();
        verify(client, times(1)).fetchAllIsvs();
    }

    @Test
    void withinTtlReturnsCachedSnapshot() {
        when(client.fetchAllAgendas()).thenReturn(List.of(new RppAgenda("a-iri", "1", "Ag")));
        when(client.fetchAllIsvs()).thenReturn(List.of(new RppIsvs("i-iri", "10", "Is", List.of())));

        RppSnapshot first = holder.get();
        clock.set(T0.plusSeconds(3600));
        RppSnapshot second = holder.get();

        assertThat(second).isSameAs(first);
        verify(client, times(1)).fetchAllAgendas();
        verify(client, times(1)).fetchAllIsvs();
    }

    @Test
    void pastTtlTriggersRefetch() {
        when(client.fetchAllAgendas()).thenReturn(List.of(new RppAgenda("a-iri", "1", "Ag")));
        when(client.fetchAllIsvs()).thenReturn(List.of(new RppIsvs("i-iri", "10", "Is", List.of())));

        holder.get();
        clock.set(T0.plusSeconds(25 * 3600));
        holder.get();

        verify(client, times(2)).fetchAllAgendas();
        verify(client, times(2)).fetchAllIsvs();
    }

    @Test
    void refetchFailureWithNonEmptyCacheServesStale() {
        when(client.fetchAllAgendas())
                .thenReturn(List.of(new RppAgenda("a-iri", "1", "Ag")))
                .thenThrow(new SparqlEndpointUnavailableException("RPP", "upstream down"));
        when(client.fetchAllIsvs()).thenReturn(List.of(new RppIsvs("i-iri", "10", "Is", List.of())));

        RppSnapshot stale = holder.get();
        clock.set(T0.plusSeconds(25 * 3600));
        RppSnapshot served = holder.get();

        assertThat(served).isSameAs(stale);
    }

    @Test
    void refetchFailureWithEmptyCacheThrows() {
        when(client.fetchAllAgendas()).thenThrow(new SparqlEndpointUnavailableException("RPP", "upstream down"));

        assertThatThrownBy(() -> holder.get()).isInstanceOf(SparqlEndpointUnavailableException.class);
    }

    @Test
    void findAgendaByIriReturnsMatch() {
        RppAgenda a1 = new RppAgenda("a1-iri", "1", "Agenda One");
        RppAgenda a2 = new RppAgenda("a2-iri", "2", "Agenda Two");
        when(client.fetchAllAgendas()).thenReturn(List.of(a1, a2));
        when(client.fetchAllIsvs()).thenReturn(List.of());

        Optional<RppAgenda> found = holder.findAgendaByIri("a2-iri");

        assertThat(found).isPresent();
        assertThat(found.get().getCode()).isEqualTo("2");
    }

    @Test
    void findAgendaByIriReturnsEmptyOnMiss() {
        when(client.fetchAllAgendas()).thenReturn(List.of(new RppAgenda("a1-iri", "1", "Ag")));
        when(client.fetchAllIsvs()).thenReturn(List.of());

        assertThat(holder.findAgendaByIri("unknown-iri")).isEmpty();
    }

    @Test
    void findAgendaByIriReturnsEmptyForNullOrBlank() {
        assertThat(holder.findAgendaByIri(null)).isEmpty();
        assertThat(holder.findAgendaByIri("  ")).isEmpty();
        verify(client, times(0)).fetchAllAgendas();
    }

    @Test
    void findIsvsByIriReturnsMatch() {
        RppIsvs i1 = new RppIsvs("i1-iri", "10", "Isvs One", List.of());
        when(client.fetchAllAgendas()).thenReturn(List.of());
        when(client.fetchAllIsvs()).thenReturn(List.of(i1));

        Optional<RppIsvs> found = holder.findIsvsByIri("i1-iri");

        assertThat(found).isPresent();
        assertThat(found.get().getNazev()).isEqualTo("Isvs One");
    }

    @Test
    void findAgendaByIriReturnsEmptyWhenSnapshotUnavailable() {
        when(client.fetchAllAgendas()).thenThrow(new SparqlEndpointUnavailableException("RPP", "upstream down"));

        assertThat(holder.findAgendaByIri("a-iri")).isEmpty();
    }

    @Test
    void findIsvsByIriReturnsEmptyWhenSnapshotUnavailable() {
        when(client.fetchAllAgendas()).thenThrow(new SparqlEndpointUnavailableException("RPP", "upstream down"));

        assertThat(holder.findIsvsByIri("i-iri")).isEmpty();
    }

    @Test
    void warmOnStartupPopulatesCacheSoFirstGetIsWarm() {
        when(client.fetchAllAgendas()).thenReturn(List.of(new RppAgenda("a-iri", "1", "Ag")));
        when(client.fetchAllIsvs()).thenReturn(List.of(new RppIsvs("i-iri", "10", "Is", List.of())));

        holder.warmOnStartup();
        RppSnapshot afterWarm = holder.get();

        // one build during warm-up; get() hits the warm cache and does not refetch
        assertThat(afterWarm.getAgendas()).hasSize(1);
        verify(client, times(1)).fetchAllAgendas();
        verify(client, times(1)).fetchAllIsvs();
    }

    @Test
    void warmOnStartupSwallowsUpstreamFailure() {
        when(client.fetchAllAgendas()).thenThrow(new SparqlEndpointUnavailableException("RPP", "upstream down"));

        // must not propagate — startup event listener stays quiet and get() falls back to lazy build
        holder.warmOnStartup();

        assertThat(holder.peek().isEmpty()).isTrue();
    }

    @Test
    void scheduledRefreshRebuildsEvenWhenSnapshotStillFresh() {
        when(client.fetchAllAgendas())
                .thenReturn(List.of(new RppAgenda("a-iri", "1", "Ag")))
                .thenReturn(List.of(new RppAgenda("a-iri", "1", "Ag"), new RppAgenda("a2-iri", "2", "Ag2")));
        when(client.fetchAllIsvs()).thenReturn(List.of(new RppIsvs("i-iri", "10", "Is", List.of())));

        holder.get();                         // initial build, snapshot fresh
        holder.scheduledRefresh();            // still within TTL, but must rebuild anyway
        RppSnapshot refreshed = holder.get();

        assertThat(refreshed.getAgendas()).hasSize(2);
        verify(client, times(2)).fetchAllAgendas();
    }

    @Test
    void scheduledRefreshFailureKeepsExistingSnapshot() {
        when(client.fetchAllAgendas())
                .thenReturn(List.of(new RppAgenda("a-iri", "1", "Ag")))
                .thenThrow(new SparqlEndpointUnavailableException("RPP", "upstream down"));
        when(client.fetchAllIsvs()).thenReturn(List.of(new RppIsvs("i-iri", "10", "Is", List.of())));

        RppSnapshot good = holder.get();
        holder.scheduledRefresh();            // upstream now down — must not evict the good snapshot
        RppSnapshot served = holder.get();

        assertThat(served).isSameAs(good);
    }
}
