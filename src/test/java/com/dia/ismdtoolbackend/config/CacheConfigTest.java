package com.dia.ismdtoolbackend.config;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.service.impl.ReferencedConceptResolutionEngine;
import com.dia.ismdtoolbackend.service.impl.WorkingCopyDeviationServiceImpl;
import com.dia.ismdtoolbackend.testsupport.cache.CacheTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The manager runs in dynamic mode, so {@code getCache} answers any name with an unbounded cache that
 * never expires. These tests read the manager straight from {@link CacheConfig}, before anything asks
 * for a cache, so {@code getCacheNames()} holds only the registered caches.
 */
class CacheConfigTest {

    private static final Set<String> CONFIGURED = Set.of(
            "esbirkaLawSearch",
            "esbirkaLawVersions",
            "esbirkaFragmentResolution",
            "esbirkaLawContent",
            "esbirkaVersionContent",
            "esbirkaRestVersions",
            "esbirkaVersionSubtreeBodies",
            ReferencedConceptResolutionEngine.CACHE_NAME,
            NkdSparqlClient.PUBLISHED_RESOURCE_CACHE,
            WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE);

    private static CacheManager freshManager() {
        return new CacheConfig().cacheManager(
                new StaticListableBeanFactory().getBeanProvider(EsbirkaConfig.class),
                new MockEnvironment().withProperty("esbirka.provider", "rest"));
    }

    @Test
    void registersExactlyTheConfiguredCaches() {
        assertThat(freshManager().getCacheNames()).containsExactlyInAnyOrderElementsOf(CONFIGURED);
    }

    @Test
    void everyConfiguredCacheHasTtlAndBound() {
        CacheTestSupport.assertBoundedWithTtl(freshManager(), CONFIGURED.toArray(String[]::new));
    }

    /** A name used in a caching annotation but never registered silently gets an unbounded cache. */
    @Test
    void everyCacheNameInProductionAnnotationsIsRegistered() throws Exception {
        Set<String> registered = Set.copyOf(freshManager().getCacheNames());
        Map<String, String> used = annotatedCacheNames();

        assertThat(used).as("caching annotations found by the scan").isNotEmpty();
        assertThat(used.keySet())
                .as("cache names used in annotations (name -> first use): %s", used)
                .isSubsetOf(registered);
    }

    private static Map<String, String> annotatedCacheNames() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return true;
            }
        };
        scanner.addIncludeFilter((reader, factory) -> true);

        Map<String, String> used = new TreeMap<>();
        ClassLoader loader = CacheConfigTest.class.getClassLoader();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.dia.ismdtoolbackend")) {
            Class<?> type = Class.forName(candidate.getBeanClassName(), false, loader);
            if (type.getProtectionDomain().getCodeSource() != null
                    && type.getProtectionDomain().getCodeSource().getLocation().getPath().contains("test-classes")) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                cacheNames(method).forEach(name -> used.putIfAbsent(name, type.getSimpleName() + "#" + method.getName()));
            }
        }
        return used;
    }

    private static Stream<String> cacheNames(Method method) {
        Stream<String> cacheable = AnnotatedElementUtils.findAllMergedAnnotations(method, Cacheable.class).stream()
                .flatMap(a -> Arrays.stream(a.cacheNames()));
        Stream<String> evict = AnnotatedElementUtils.findAllMergedAnnotations(method, CacheEvict.class).stream()
                .flatMap(a -> Arrays.stream(a.cacheNames()));
        Stream<String> put = AnnotatedElementUtils.findAllMergedAnnotations(method, CachePut.class).stream()
                .flatMap(a -> Arrays.stream(a.cacheNames()));
        Caching caching = method.getAnnotation(Caching.class);
        Stream<String> composed = caching == null ? Stream.empty() : Stream.of(
                        Arrays.stream(caching.cacheable()).flatMap(a -> Arrays.stream(a.cacheNames())),
                        Arrays.stream(caching.evict()).flatMap(a -> Arrays.stream(a.cacheNames())),
                        Arrays.stream(caching.put()).flatMap(a -> Arrays.stream(a.cacheNames())))
                .flatMap(s -> s);
        return Stream.of(cacheable, evict, put, composed).flatMap(s -> s);
    }
}
