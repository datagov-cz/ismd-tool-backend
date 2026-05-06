package com.dia.ismdtoolbackend.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Caffeine-backed cache for read-only e-Sbírka catalogue endpoints.
 * 60-minute TTL on {@code esbirkaLawSearch} and {@code esbirkaLawVersions};
 * fragments are not cached (large payload, rarely re-requested per session).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    static final long ESBIRKA_TTL_MINUTES = 60;
    static final long ESBIRKA_MAX_ENTRIES = 1_000;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager();
        mgr.setCacheNames(List.of("esbirkaLawSearch", "esbirkaLawVersions"));
        mgr.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(ESBIRKA_TTL_MINUTES, TimeUnit.MINUTES)
                .maximumSize(ESBIRKA_MAX_ENTRIES));
        return mgr;
    }
}
