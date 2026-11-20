package com.dia.ismdtoolbackend.config;

import com.dia.ismdtoolbackend.service.search.SearchTasks;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SearchConfigTest {

    /** Core below max would cap concurrency at core: the pool grows only once its queue is full. */
    @Test
    void poolsRunTheirFullSizeConcurrently() throws Exception {
        ThreadPoolTaskExecutor pool = SearchConfig.fixedPool("t-", 4, 100);
        CountDownLatch allRunning = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 4; i++) {
                pool.execute(() -> {
                    allRunning.countDown();
                    awaitQuietly(release);
                });
            }
            assertThat(allRunning.await(2, TimeUnit.SECONDS)).as("4 tasks run at once").isTrue();
        } finally {
            release.countDown();
            pool.shutdown();
        }
    }

    /**
     * The nesting the two pools exist for: every source task waits on a query of its own. On one pool of 2
     * with 2 such source tasks, the queries would sit in the queue until timeout.
     */
    @Test
    void sourceTasksWaitingOnTheirQueriesDoNotStarve() {
        ThreadPoolTaskExecutor sources = SearchConfig.fixedPool("s-", 2, 10);
        ThreadPoolTaskExecutor queries = SearchConfig.fixedPool("q-", 2, 10);
        try {
            List<CompletableFuture<String>> searches = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                searches.add(SearchTasks.supply(sources, 2_000,
                        () -> SearchTasks.supply(queries, 2_000, () -> "rows").join()));
            }
            assertThat(searches).allSatisfy(s -> assertThat(s.join()).isEqualTo("rows"));
        } finally {
            sources.shutdown();
            queries.shutdown();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
