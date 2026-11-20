package com.dia.ismdtoolbackend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped {@code application.properties} gives the shared {@code @Scheduled} pool more than one thread,
 * so one long job cannot hold back the others. Guards against the setting being dropped.
 */
class SchedulingPoolSizeTest {

    @Configuration
    @EnableScheduling
    static class Scheduling {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(Scheduling.class)
            .withInitializer(context -> {
                try {
                    new PropertiesPropertySourceLoader()
                            .load("application.properties", new ClassPathResource("application.properties"))
                            .forEach(context.getEnvironment().getPropertySources()::addLast);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });

    @Test
    void sharedSchedulerPool_hasFourThreadsByDefault() {
        runner.run(context -> assertThat(configuredThreads(context.getBean(ThreadPoolTaskScheduler.class)))
                .isEqualTo(4));
    }

    @Test
    void sharedSchedulerPool_isOverridablePerEnvironment() {
        runner.withPropertyValues("SCHEDULING_POOL_SIZE=6")
                .run(context -> assertThat(configuredThreads(context.getBean(ThreadPoolTaskScheduler.class)))
                        .isEqualTo(6));
    }

    /** The configured size; {@code getPoolSize()} counts only threads started so far. */
    private static int configuredThreads(ThreadPoolTaskScheduler scheduler) {
        return scheduler.getScheduledThreadPoolExecutor().getCorePoolSize();
    }
}
