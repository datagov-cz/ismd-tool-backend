package com.dia.ismdtoolbackend.outbox;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configuration for the PG↔TDB2 transactional outbox.
 *
 * <p>When {@link #enabled} is {@code false} (the default) the outboxed write sites fall back to
 * their existing synchronous direct-write path, so deploying this code changes no behavior until
 * an environment opts in — the same dark-ship seam the reconciler uses. The relay drains on the
 * {@link #relayCron} backstop schedule; the hot path is driven by an after-commit nudge per write.
 *
 * <p>Mirrors {@code ReconcilerConfig} style ({@code @Data @Configuration @ConfigurationProperties}).
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "outbox")
public class OutboxConfig {

    /**
     * Master switch. When {@code false}, the four outboxed write sites use the legacy direct TDB2
     * write instead of enqueuing — no relay, no behavior change. Off by default everywhere.
     */
    private boolean enabled = false;

    /** Cron for the backstop relay drain (Spring 6-field). Frequent: the after-commit nudge handles the hot path; this only catches rows a crash left behind. */
    private String relayCron = "*/10 * * * * *";

    /** Max apply attempts before a row is marked {@code FAILED} (and blocks its aggregate until an admin retries). */
    private int maxAttempts = 10;

    /** Max rows claimed per relay drain pass. */
    private int batchSize = 100;

    /** Retention for {@code DONE} rows before the prune removes them (they double as a write-path audit trail). */
    private Duration doneRetention = Duration.ofDays(7);
}
