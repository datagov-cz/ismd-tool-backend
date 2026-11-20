package com.dia.ismdtoolbackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Two fixed-size pools, because search nests: a per-source task ({@code searchExecutor}) waits on the PG and
 * Fuseki queries it starts ({@code searchQueryExecutor}). On one pool, waiting source tasks can hold every
 * thread while their own queries sit in the queue until they time out.
 */
@Configuration
public class SearchConfig {

    @Bean(name = "searchExecutor", destroyMethod = "shutdown")
    public Executor searchExecutor(
            @Value("${search.source-executor.pool-size:8}") int poolSize,
            @Value("${search.source-executor.queue-capacity:32}") int queueCapacity) {
        return fixedPool("search-", poolSize, queueCapacity);
    }

    @Bean(name = "searchQueryExecutor", destroyMethod = "shutdown")
    public Executor searchQueryExecutor(
            @Value("${search.query-executor.pool-size:16}") int poolSize,
            @Value("${search.query-executor.queue-capacity:64}") int queueCapacity) {
        return fixedPool("search-query-", poolSize, queueCapacity);
    }

    /**
     * Core equals max: a {@code ThreadPoolTaskExecutor} grows past its core size only once the queue is full,
     * so a smaller core would cap concurrency at the core size. A full queue rejects the task.
     */
    static ThreadPoolTaskExecutor fixedPool(String prefix, int poolSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(prefix);
        executor.initialize();
        return executor;
    }
}
