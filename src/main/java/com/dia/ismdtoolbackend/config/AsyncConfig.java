package com.dia.ismdtoolbackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Enables {@code @Async} and provides the executor for NKD snapshot warming — the off-request-thread
 * pass that fetches/materializes/evaluates published-concept local copies so heavy reads (ontology
 * detail) never block on NKD. Mirrors {@link SearchConfig#searchExecutor}.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "snapshotExecutor", destroyMethod = "shutdown")
    public Executor snapshotExecutor(
            @Value("${nkd.snapshot.executor.core-pool-size:2}") int corePoolSize,
            @Value("${nkd.snapshot.executor.max-pool-size:8}") int maxPoolSize,
            @Value("${nkd.snapshot.executor.queue-capacity:100}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("snapshot-");
        executor.initialize();
        return executor;
    }
}
