package com.dia.ismdtoolbackend.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine-backed caches for read-only e-Sbírka SPARQL operations.
 *
 * <p>Three caches with distinct lifetimes:
 * <ul>
 *   <li>{@code esbirkaLawSearch} — law-search results, 60 min TTL (catalogue evolves daily).</li>
 *   <li>{@code esbirkaLawVersions} — version lists per law, 60 min TTL.</li>
 *   <li>{@code esbirkaFragmentResolution} — fragment citation + version metadata,
 *       24 h TTL (fragments are immutable; only is-latest can shift when a new
 *       version is published).</li>
 * </ul>
 *
 * <p>Per-cache specs require {@code registerCustomCache} rather than the shared
 * {@code setCaffeine}.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    static final long ESBIRKA_CATALOGUE_TTL_MINUTES = 60;
    static final long ESBIRKA_CATALOGUE_MAX_ENTRIES = 1_000;

    static final long ESBIRKA_RESOLUTION_TTL_HOURS = 24;
    static final long ESBIRKA_RESOLUTION_MAX_ENTRIES = 5_000;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager();

        mgr.registerCustomCache("esbirkaLawSearch", Caffeine.newBuilder()
                .expireAfterWrite(ESBIRKA_CATALOGUE_TTL_MINUTES, TimeUnit.MINUTES)
                .maximumSize(ESBIRKA_CATALOGUE_MAX_ENTRIES)
                .build());

        mgr.registerCustomCache("esbirkaLawVersions", Caffeine.newBuilder()
                .expireAfterWrite(ESBIRKA_CATALOGUE_TTL_MINUTES, TimeUnit.MINUTES)
                .maximumSize(ESBIRKA_CATALOGUE_MAX_ENTRIES)
                .build());

        mgr.registerCustomCache("esbirkaFragmentResolution", Caffeine.newBuilder()
                .expireAfterWrite(ESBIRKA_RESOLUTION_TTL_HOURS, TimeUnit.HOURS)
                .maximumSize(ESBIRKA_RESOLUTION_MAX_ENTRIES)
                .build());

        return mgr;
    }
}
