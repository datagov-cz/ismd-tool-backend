package com.dia.ismdtoolbackend.actuator;

import com.dia.ismdtoolbackend.models.rpp.RppAgenda;
import com.dia.ismdtoolbackend.models.rpp.RppIsvs;
import com.dia.ismdtoolbackend.models.rpp.RppSnapshot;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RppHealthIndicatorTest {

    private static final Instant NOW = Instant.parse("2026-04-23T12:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private RppSnapshotHolder holder;

    @Test
    void emptySnapshotReportsUnknown() {
        when(holder.peek()).thenReturn(RppSnapshot.empty());

        Health health = new RppHealthIndicator(holder, FIXED).health();

        assertEquals(Status.UNKNOWN, health.getStatus());
        assertEquals("not-yet-loaded", health.getDetails().get("reason"));
    }

    @Test
    void freshSnapshotReportsUpWithAgeAndCounts() {
        Instant loadedAt = NOW.minusSeconds(600);
        RppAgenda agenda = new RppAgenda("iri:a", "A1", "Agenda 1");
        RppIsvs isvs = new RppIsvs("iri:i", "1", "ISVS 1", List.of());
        RppSnapshot snap = new RppSnapshot(loadedAt, List.of(agenda), List.of(isvs), Map.of("iri:i", List.of()));
        when(holder.peek()).thenReturn(snap);

        Health health = new RppHealthIndicator(holder, FIXED).health();

        assertEquals(Status.UP, health.getStatus());
        Map<String, Object> details = health.getDetails();
        assertEquals(loadedAt.toString(), details.get("loadedAt"));
        assertEquals(600L, details.get("ageSeconds"));
        assertEquals(1, details.get("agendaCount"));
        assertEquals(1, details.get("isvsCount"));
    }

    @Test
    void staleSnapshotStillReportsUp() {
        Instant loadedAt = NOW.minusSeconds(7 * 24 * 3600);
        RppAgenda agenda = new RppAgenda("iri:a", "A1", "Agenda 1");
        RppSnapshot snap = new RppSnapshot(loadedAt, List.of(agenda), List.of(), Map.of());
        when(holder.peek()).thenReturn(snap);

        Health health = new RppHealthIndicator(holder, FIXED).health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(7 * 24 * 3600L, health.getDetails().get("ageSeconds"));
    }
}
