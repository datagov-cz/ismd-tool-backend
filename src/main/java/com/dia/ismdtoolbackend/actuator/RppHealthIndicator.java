package com.dia.ismdtoolbackend.actuator;

import com.dia.ismdtoolbackend.models.rpp.RppSnapshot;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

@Component("rpp")
public class RppHealthIndicator implements HealthIndicator {

    private final RppSnapshotHolder holder;
    private final Clock clock;

    public RppHealthIndicator(RppSnapshotHolder holder, Clock clock) {
        this.holder = holder;
        this.clock = clock;
    }

    @Override
    public Health health() {
        RppSnapshot snap = holder.peek();
        if (snap.isEmpty()) {
            return Health.unknown()
                    .withDetail("reason", "not-yet-loaded")
                    .build();
        }
        long ageSeconds = Duration.between(snap.getLoadedAt(), clock.instant()).toSeconds();
        return Health.up()
                .withDetail("loadedAt", snap.getLoadedAt().toString())
                .withDetail("ageSeconds", ageSeconds)
                .withDetail("agendaCount", snap.getAgendas().size())
                .withDetail("isvsCount", snap.getIsvs().size())
                .build();
    }
}
