package com.dia.ismdtoolbackend.testsupport.cache;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

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
}
