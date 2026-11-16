package com.dia.ismdtoolbackend.testsupport.cache;

import com.github.benmanes.caffeine.cache.Policy;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;

import static org.assertj.core.api.Assertions.assertThat;

/** Cache state helpers for tests that run through Spring's caching proxies. */
public final class CacheTestSupport {

    private CacheTestSupport() {
    }

    /**
     * Empties every cache the manager knows, so no test sees entries another test left behind
     * in a context Spring reuses across tests and classes.
     */
    public static void clearAll(CacheManager cacheManager) {
        for (String name : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    /**
     * Asserts that each named cache is one {@code CacheConfig} registers, with a write TTL and a size
     * or weight bound. A non-null {@code getCache} proves nothing: the manager creates any unknown name
     * on demand as an unbounded cache without expiry.
     */
    public static void assertBoundedWithTtl(CacheManager cacheManager, String... names) {
        for (String name : names) {
            Cache cache = cacheManager.getCache(name);
            assertThat(cache).as(name).isInstanceOf(CaffeineCache.class);
            Policy<Object, Object> policy = ((CaffeineCache) cache).getNativeCache().policy();
            assertThat(policy.expireAfterWrite()).as("%s expireAfterWrite", name).isPresent();
            assertThat(policy.eviction()).as("%s size/weight bound", name).isPresent();
        }
    }
}
