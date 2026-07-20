package com.dia.ismdtoolbackend.service.rpp;

import com.dia.ismdtoolbackend.client.RppSparqlClient;
import com.dia.ismdtoolbackend.config.RppConfig;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.models.rpp.RppAgenda;
import com.dia.ismdtoolbackend.models.rpp.RppIsvs;
import com.dia.ismdtoolbackend.models.rpp.RppSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RppSnapshotHolderTest {

    private static final Instant T0 = Instant.parse("2026-04-21T12:00:00Z");

    @Mock
    private RppSparqlClient client;

    private AtomicReference<Instant> now;
    private Clock clock;
    private RppSnapshotHolder holder;

    @BeforeEach
    void setUp() {
        now = new AtomicReference<>(T0);
        clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        RppConfig config = new RppConfig();
        config.getCache().setTtlHours(24);
        holder = new RppSnapshotHolder(client, clock, config);
    }

    @Test
    void coldFetchPopulatesCache() {
        when(client.fetchAllAgendas()).thenReturn(List.of(new RppAgenda("a-iri", "1", "Ag")));
        when(client.fetchAllIsvs()).thenReturn(List.of(new RppIsvs("i-iri", "10", "Is", List.of())));

        RppSnapshot snap = holder.get();

        assertEquals(T0, snap.getLoadedAt());
        assertEquals(1, snap.getAgendas().size());
        assertEquals(1, snap.getIsvs().size());
        verify(client, times(1)).fetchAllAgendas();
        verify(client, times(1)).fetchAllIsvs();
    }

    @Test
    void withinTtlReturnsCachedSnapshot() {
        when(client.fetchAllAgendas()).thenReturn(List.of(new RppAgenda("a-iri", "1", "Ag")));
        when(client.fetchAllIsvs()).thenReturn(List.of(new RppIsvs("i-iri", "10", "Is", List.of())));

        RppSnapshot first = holder.get();
        now.set(T0.plusSeconds(3600));
        RppSnapshot second = holder.get();

        assertSame(first, second);
        verify(client, times(1)).fetchAllAgendas();
        verify(client, times(1)).fetchAllIsvs();
    }

    @Test
    void pastTtlTriggersRefetch() {
        when(client.fetchAllAgendas()).thenReturn(List.of(new RppAgenda("a-iri", "1", "Ag")));
        when(client.fetchAllIsvs()).thenReturn(List.of(new RppIsvs("i-iri", "10", "Is", List.of())));

        holder.get();
        now.set(T0.plusSeconds(25 * 3600));
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
        now.set(T0.plusSeconds(25 * 3600));
        RppSnapshot served = holder.get();

        assertSame(stale, served);
    }

    @Test
    void refetchFailureWithEmptyCacheThrows() {
        when(client.fetchAllAgendas()).thenThrow(new SparqlEndpointUnavailableException("RPP", "upstream down"));

        assertThrows(SparqlEndpointUnavailableException.class, () -> holder.get());
    }

    @Test
    void findAgendaByIriReturnsMatch() {
        RppAgenda a1 = new RppAgenda("a1-iri", "1", "Agenda One");
        RppAgenda a2 = new RppAgenda("a2-iri", "2", "Agenda Two");
        when(client.fetchAllAgendas()).thenReturn(List.of(a1, a2));
        when(client.fetchAllIsvs()).thenReturn(List.of());

        Optional<RppAgenda> found = holder.findAgendaByIri("a2-iri");

        assertTrue(found.isPresent());
        assertEquals("2", found.get().getCode());
    }

    @Test
    void findAgendaByIriReturnsEmptyOnMiss() {
        when(client.fetchAllAgendas()).thenReturn(List.of(new RppAgenda("a1-iri", "1", "Ag")));
        when(client.fetchAllIsvs()).thenReturn(List.of());

        assertTrue(holder.findAgendaByIri("unknown-iri").isEmpty());
    }

    @Test
    void findAgendaByIriReturnsEmptyForNullOrBlank() {
        assertTrue(holder.findAgendaByIri(null).isEmpty());
        assertTrue(holder.findAgendaByIri("  ").isEmpty());
        verify(client, times(0)).fetchAllAgendas();
    }

    @Test
    void findIsvsByIriReturnsMatch() {
        RppIsvs i1 = new RppIsvs("i1-iri", "10", "Isvs One", List.of());
        when(client.fetchAllAgendas()).thenReturn(List.of());
        when(client.fetchAllIsvs()).thenReturn(List.of(i1));

        Optional<RppIsvs> found = holder.findIsvsByIri("i1-iri");

        assertTrue(found.isPresent());
        assertEquals("Isvs One", found.get().getNazev());
    }

    @Test
    void findAgendaByIriReturnsEmptyWhenSnapshotUnavailable() {
        when(client.fetchAllAgendas()).thenThrow(new SparqlEndpointUnavailableException("RPP", "upstream down"));

        assertTrue(holder.findAgendaByIri("a-iri").isEmpty());
    }

    @Test
    void findIsvsByIriReturnsEmptyWhenSnapshotUnavailable() {
        when(client.fetchAllAgendas()).thenThrow(new SparqlEndpointUnavailableException("RPP", "upstream down"));

        assertTrue(holder.findIsvsByIri("i-iri").isEmpty());
    }

    @Test
    void warmOnStartupLoadsTheSnapshotSoTheFirstRequestDoesNot() {
        when(client.fetchAllAgendas()).thenReturn(List.of(new RppAgenda("a-iri", "1", "Ag")));
        when(client.fetchAllIsvs()).thenReturn(List.of(new RppIsvs("i-iri", "10", "Is", List.of())));

        holder.warmOnStartup();

        // A subsequent lookup is served from the warmed snapshot — the client is not hit again.
        assertTrue(holder.findAgendaByIri("a-iri").isPresent());
        verify(client, times(1)).fetchAllAgendas();
        verify(client, times(1)).fetchAllIsvs();
    }

    @Test
    void warmOnStartupSwallowsFailure_soAppStartIsNotBlocked() {
        when(client.fetchAllAgendas()).thenThrow(new SparqlEndpointUnavailableException("RPP", "upstream down"));

        // Must not propagate: RPP being down cannot stop the app from starting.
        holder.warmOnStartup();

        assertTrue(holder.peek().isEmpty());
    }
}
