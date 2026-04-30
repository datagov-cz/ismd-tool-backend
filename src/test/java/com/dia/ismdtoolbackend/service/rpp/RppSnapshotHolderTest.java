package com.dia.ismdtoolbackend.service.rpp;

import com.dia.ismdtoolbackend.client.RppSparqlClient;
import com.dia.ismdtoolbackend.config.RppConfig;
import com.dia.ismdtoolbackend.exception.RppUnavailableException;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                .thenThrow(new RppUnavailableException("upstream down"));
        when(client.fetchAllIsvs()).thenReturn(List.of(new RppIsvs("i-iri", "10", "Is", List.of())));

        RppSnapshot stale = holder.get();
        now.set(T0.plusSeconds(25 * 3600));
        RppSnapshot served = holder.get();

        assertSame(stale, served);
    }

    @Test
    void refetchFailureWithEmptyCacheThrows() {
        when(client.fetchAllAgendas()).thenThrow(new RppUnavailableException("upstream down"));

        assertThrows(RppUnavailableException.class, () -> holder.get());
    }
}
