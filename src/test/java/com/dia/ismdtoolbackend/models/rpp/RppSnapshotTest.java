package com.dia.ismdtoolbackend.models.rpp;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RppSnapshotTest {

    private static final Instant NOW = Instant.parse("2026-04-21T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void emptySentinelIsEmpty() {
        RppSnapshot snap = RppSnapshot.empty();
        assertTrue(snap.isEmpty());
        assertTrue(snap.isStale(Duration.ofHours(24), FIXED_CLOCK));
    }

    @Test
    void populatedSnapshotWithinTtlIsFresh() {
        RppSnapshot snap = populated(NOW.minus(Duration.ofHours(23)));
        assertFalse(snap.isEmpty());
        assertFalse(snap.isStale(Duration.ofHours(24), FIXED_CLOCK));
    }

    @Test
    void populatedSnapshotPastTtlIsStale() {
        RppSnapshot snap = populated(NOW.minus(Duration.ofHours(25)));
        assertTrue(snap.isStale(Duration.ofHours(24), FIXED_CLOCK));
    }

    private static RppSnapshot populated(Instant loadedAt) {
        RppAgenda a = new RppAgenda("iri-a", "1", "Agenda 1");
        RppIsvs i = new RppIsvs("iri-i", "10", "ISVS 10", List.of());
        return new RppSnapshot(loadedAt, List.of(a), List.of(i), Map.of());
    }
}
