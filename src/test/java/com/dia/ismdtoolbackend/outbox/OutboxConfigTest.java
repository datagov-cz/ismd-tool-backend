package com.dia.ismdtoolbackend.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3 — binding test for {@link OutboxConfig}: defaults are observe-only/off, and the kebab-case
 * properties (incl. the {@code Duration} and the {@code relay-cron} → {@code relayCron} mapping)
 * bind. Uses {@link ApplicationContextRunner} so it needs no DB/container.
 */
class OutboxConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @EnableConfigurationProperties(OutboxConfig.class)
    static class TestConfig {
    }

    @Test
    void defaults_areOffAndObserveOnly() {
        runner.run(ctx -> {
            OutboxConfig config = ctx.getBean(OutboxConfig.class);
            assertThat(config.isEnabled()).isFalse();
            assertThat(config.getRelayCron()).isEqualTo("*/10 * * * * *");
            assertThat(config.getMaxAttempts()).isEqualTo(10);
            assertThat(config.getBatchSize()).isEqualTo(100);
            assertThat(config.getDoneRetention()).isEqualTo(Duration.ofDays(7));
        });
    }

    @Test
    void properties_bindIncludingKebabCaseAndDuration() {
        runner.withPropertyValues(
                "outbox.enabled=true",
                "outbox.relay-cron=0 */5 * * * *",
                "outbox.max-attempts=3",
                "outbox.batch-size=50",
                "outbox.done-retention=PT1H"
        ).run(ctx -> {
            OutboxConfig config = ctx.getBean(OutboxConfig.class);
            assertThat(config.isEnabled()).isTrue();
            assertThat(config.getRelayCron()).isEqualTo("0 */5 * * * *");
            assertThat(config.getMaxAttempts()).isEqualTo(3);
            assertThat(config.getBatchSize()).isEqualTo(50);
            assertThat(config.getDoneRetention()).isEqualTo(Duration.ofHours(1));
        });
    }
}
