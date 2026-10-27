package com.dia.ismdtoolbackend.config;

import com.dia.ismdtoolbackend.controller.dto.FragmentDto;
import com.dia.ismdtoolbackend.controller.dto.LawContentDto;
import com.dia.ismdtoolbackend.models.eli.FragmentModel;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cache.CacheManager;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EsbirkaContentCacheBudgetTest {

    private final EsbirkaContentWeigher weigher = new EsbirkaContentWeigher();

    @Test
    void versionContentWeighsTwoBytesPerCharacterPlusOverhead() {
        String body = "x".repeat(10_000);
        List<FragmentModel> content = List.of(new FragmentModel("i", null, null, "par", "000000", body));

        int weight = weigher.weigh("/sb/1997/49/2025-11-01", content);

        assertThat(weight >= 20_000 && weight < 21_000).as("weight " + weight).isTrue();
    }

    @Test
    void renderedContentCountsTheTreeAndTheWholeBody() {
        FragmentDto child = new FragmentDto();
        child.setBodyHtml("y".repeat(5_000));
        FragmentDto root = new FragmentDto();
        root.setChildren(new ArrayList<>(List.of(child)));
        LawContentDto content = LawContentDto.builder().bodyHtml("z".repeat(5_000)).fragments(List.of(root)).build();

        assertThat(weigher.weigh("49/1997@", content)).isGreaterThanOrEqualTo(20_000);
    }

    @Test
    void underRestTheTwoContentCachesSplitTheBudget() {
        CacheManager manager = manager("rest", 1);
        Cache<Object, Object> lawContent = nativeCache(manager, "esbirkaLawContent");
        Cache<Object, Object> versionContent = nativeCache(manager, "esbirkaVersionContent");

        assertThat(versionContent)
                .as("a load nested in a load on one Caffeine cache is forbidden")
                .isNotSameAs(lawContent);
        assertThat(lawContent.policy().eviction().orElseThrow().getMaximum()).isEqualTo(512 * 1024);
        assertThat(versionContent.policy().eviction().orElseThrow().getMaximum()).isEqualTo(512 * 1024);
    }

    @Test
    void underSparqlRenderedContentKeepsTheWholeBudget() {
        CacheManager manager = manager("sparql", 1);

        assertThat(nativeCache(manager, "esbirkaLawContent").policy().eviction().orElseThrow().getMaximum())
                .isEqualTo(1024 * 1024);
        assertThat(nativeCache(manager, "esbirkaVersionContent").policy().eviction().orElseThrow().getMaximum())
                .isEqualTo(0);
    }

    @Test
    void contentEvictsByWeight() {
        CacheManager manager = manager("rest", 1);
        List<FragmentModel> twoHundredKb = List.of(new FragmentModel("i", null, null, "par", "0", "x".repeat(100_000)));
        for (int i = 0; i < 4; i++) {
            manager.getCache("esbirkaVersionContent").put("/sb/" + i + "/1/2020-01-01", twoHundredKb);
        }
        Cache<Object, Object> versionContent = nativeCache(manager, "esbirkaVersionContent");
        versionContent.cleanUp();

        assertThat(versionContent.estimatedSize()).as("0.5 MB holds two of the four ~0.2 MB entries").isEqualTo(2);
    }

    /**
     * {@code getLawContent} (esbirkaLawContent) loads the version through esbirkaVersionContent inside
     * its own load. On one shared Caffeine instance this failed ~0.5 % of loads with "Recursive update"
     * and deadlocked concurrent ones; here it runs concurrently with eviction pressure.
     */
    @Test
    void nestedColdLoadsUnderConcurrencyNeverFail() throws Exception {
        CacheManager manager = manager("rest", 8);
        var outer = manager.getCache("esbirkaLawContent");
        var inner = manager.getCache("esbirkaVersionContent");
        List<FragmentModel> halfMb = List.of(new FragmentModel("i", null, null, "par", "0", "x".repeat(250_000)));
        AtomicInteger next = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                workers.add(pool.submit(() -> {
                    for (int i = next.incrementAndGet(); i <= 3_000; i = next.incrementAndGet()) {
                        int n = i;
                        try {
                            outer.get(n + "/1997@v", () -> {
                                inner.get("/sb/1997/" + n + "/2025-11-01", () -> halfMb);
                                return LawContentDto.builder().bodyHtml("z").build();
                            });
                        } catch (RuntimeException e) {
                            failures.incrementAndGet();
                        }
                    }
                }));
            }
            for (Future<?> worker : workers) {
                worker.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures.get()).isEqualTo(0);
    }

    private static CacheManager manager(String provider, int budgetMb) {
        EsbirkaConfig esbirka = new EsbirkaConfig();
        esbirka.setCacheBudgetMb(budgetMb);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("esbirkaConfig", esbirka);
        return new CacheConfig().cacheManager(beans.getBeanProvider(EsbirkaConfig.class),
                new MockEnvironment().withProperty("esbirka.provider", provider));
    }

    @SuppressWarnings("unchecked")
    private static Cache<Object, Object> nativeCache(CacheManager manager, String name) {
        return (Cache<Object, Object>) manager.getCache(name).getNativeCache();
    }
}
