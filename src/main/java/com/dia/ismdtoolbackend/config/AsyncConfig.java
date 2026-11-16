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

    public static final String UPLOAD_VALIDATION_EXECUTOR = "uploadValidationExecutor";

    /** Runs the advisory validation an upload starts after its commit; the call blocks up to the validator's read timeout. */
    @Bean(name = UPLOAD_VALIDATION_EXECUTOR, destroyMethod = "shutdown")
    public Executor uploadValidationExecutor(
            @Value("${upload.validation.executor.core-pool-size:1}") int corePoolSize,
            @Value("${upload.validation.executor.max-pool-size:4}") int maxPoolSize,
            @Value("${upload.validation.executor.queue-capacity:50}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("upload-validation-");
        executor.initialize();
        return executor;
    }

    public static final String PUBLICATION_EXECUTOR = "publicationExecutor";

    /** Runs the publication checks off the boot and scheduler threads; one run at a time, extra triggers dropped. */
    @Bean(name = PUBLICATION_EXECUTOR, destroyMethod = "shutdown")
    public Executor publicationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        executor.setThreadNamePrefix("publication-");
        executor.initialize();
        return executor;
    }

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
