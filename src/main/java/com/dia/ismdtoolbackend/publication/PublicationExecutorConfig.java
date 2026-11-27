package com.dia.ismdtoolbackend.publication;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The thread a publication run builds on. One thread: {@link PublicationService} admits one run at a
 * time. On shutdown the running build is interrupted and given time to record itself as failed; a run
 * cut off harder is failed on the next startup.
 */
@Configuration
public class PublicationExecutorConfig {

    public static final String PUBLICATION_EXECUTOR = "publicationExecutor";
    private static final int AWAIT_TERMINATION_SECONDS = 30;

    @Bean(name = PUBLICATION_EXECUTOR, destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor publicationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        // room for the next run while the previous run's thread is still returning to the pool
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("publication-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);
        executor.initialize();
        return executor;
    }
}
