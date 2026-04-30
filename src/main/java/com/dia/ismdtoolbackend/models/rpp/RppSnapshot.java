package com.dia.ismdtoolbackend.models.rpp;

import lombok.Value;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Value
public class RppSnapshot {
    Instant loadedAt;
    List<RppAgenda> agendas;
    List<RppIsvs> isvs;
    Map<String, List<String>> agendaIrisByIsvsIri;

    public static RppSnapshot empty() {
        return new RppSnapshot(Instant.EPOCH, List.of(), List.of(), Map.of());
    }

    public boolean isEmpty() {
        return agendas.isEmpty() && isvs.isEmpty();
    }

    public boolean isStale(Duration ttl, Clock clock) {
        return loadedAt.plus(ttl).isBefore(clock.instant());
    }
}