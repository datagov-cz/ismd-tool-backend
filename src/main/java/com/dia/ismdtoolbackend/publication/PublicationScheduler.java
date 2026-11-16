package com.dia.ismdtoolbackend.publication;

import com.dia.ismdtoolbackend.config.AsyncConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes every {@code ismd.publication.interval}. Cron cannot express "every 30 days" and a fixed rate
 * restarts with the application, so a daily {@code check-cron} tick publishes only when the current
 * publication, whose age survives restarts, has reached the interval. Gated by
 * {@code ismd.publication.enabled}.
 *
 * <p>Single-instance assumption, as for the reconciler: each replica would publish into its own directory.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublicationScheduler {

    private final PublicationConfig config;
    private final PublicationService service;

    /** Publishes off the boot thread when nothing is published yet or the publication is overdue. */
    @Async(AsyncConfig.PUBLICATION_EXECUTOR)
    @EventListener(ApplicationReadyEvent.class)
    public void publishOnStartupIfDue() {
        tick("startup");
    }

    @Scheduled(cron = "${ismd.publication.check-cron:0 0 3 * * *}")
    public void publishScheduledIfDue() {
        tick("scheduled");
    }

    private void tick(String trigger) {
        if (!config.isEnabled()) {
            log.debug("Publication {} check skipped — ismd.publication.enabled=false", trigger);
            return;
        }
        try {
            if (service.publishIfDue()) {
                log.info("Publication {} check published a new publication", trigger);
            }
        } catch (Exception e) {
            // A failed run must not kill the scheduler thread.
            log.error("Publication {} check failed", trigger, e);
        }
    }
}
