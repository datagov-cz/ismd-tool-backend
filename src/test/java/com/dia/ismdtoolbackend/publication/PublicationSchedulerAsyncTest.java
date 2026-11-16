package com.dia.ismdtoolbackend.publication;

import com.dia.ismdtoolbackend.config.AsyncConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Both checks leave the calling thread, so a run never holds the shared scheduler thread. */
@SpringJUnitConfig(PublicationSchedulerAsyncTest.Beans.class)
class PublicationSchedulerAsyncTest {

    private static final CompletableFuture<String> scheduledThread = new CompletableFuture<>();
    private static final CompletableFuture<String> startupThread = new CompletableFuture<>();

    @Configuration
    @Import({AsyncConfig.class, PublicationScheduler.class})
    static class Beans {

        @Bean
        PublicationConfig publicationConfig() {
            PublicationConfig config = new PublicationConfig();
            config.setEnabled(true);
            return config;
        }

        @Bean
        PublicationService publicationService() {
            PublicationService service = mock(PublicationService.class);
            when(service.publishIfDue()).thenAnswer(invocation -> {
                String name = Thread.currentThread().getName();
                if (!scheduledThread.complete(name)) {
                    startupThread.complete(name);
                }
                return false;
            });
            return service;
        }
    }

    @Autowired private PublicationScheduler scheduler;

    @Test
    void checksRunOnThePublicationExecutor() throws Exception {
        scheduler.publishScheduledIfDue();
        assertThat(scheduledThread.get(5, TimeUnit.SECONDS)).startsWith("publication-");

        scheduler.publishOnStartupIfDue();
        assertThat(startupThread.get(5, TimeUnit.SECONDS)).startsWith("publication-");
    }
}
