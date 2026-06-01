package com.dia.ismdtoolbackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class SearchConfig {

    @Bean(name = "searchExecutor", destroyMethod = "shutdown")
    public Executor searchExecutor(
            @Value("${search.executor.core-pool-size:4}") int corePoolSize,
            @Value("${search.executor.max-pool-size:16}") int maxPoolSize,
            @Value("${search.executor.queue-capacity:100}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("search-");
        executor.initialize();
        return executor;
    }
}