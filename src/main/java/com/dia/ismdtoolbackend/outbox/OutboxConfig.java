package com.dia.ismdtoolbackend.outbox;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configuration for the PG↔TDB2 transactional outbox.
 *
 * <p>The relay drains on the {@link #relayCron} backstop schedule; the hot path is driven by an
 * after-commit nudge per write.
 *
 * <p>Mirrors {@code ReconcilerConfig} style ({@code @Data @Configuration @ConfigurationProperties}).
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "outbox")
public class OutboxConfig {

    /** Cron for the backstop relay drain (Spring 6-field). Frequent: the after-commit nudge handles the hot path; this only catches rows a crash left behind. */
    private String relayCron = "*/10 * * * * *";

    /** Max apply attempts before a row is marked {@code FAILED} (and blocks its aggregate until an admin retries). */
    private int maxAttempts = 10;

    /** Max rows claimed per relay drain pass. */
    private int batchSize = 100;

    /** Retention for {@code DONE} rows before the prune removes them (they double as a write-path audit trail). */
    private Duration doneRetention = Duration.ofDays(7);

    /** Cron for the {@code DONE}-row retention prune (Spring 6-field). Default daily at 03:30. */
    private String pruneCron = "0 30 3 * * *";
}
