package com.dia.ismdtoolbackend.service.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ArrayBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchTasksTest {

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    void completesWithTheWorksResult() {
        assertThat(SearchTasks.supply(pool, 1_000, () -> "hit").join()).isEqualTo("hit");
    }

    @Test
    void aFailureCompletesTheFutureExceptionally() {
        CompletableFuture<Object> future = SearchTasks.supply(pool, 1_000, () -> {
            throw new IllegalStateException("fuseki down");
        });

        assertThatThrownBy(future::join).hasCauseInstanceOf(IllegalStateException.class);
    }

    /** The point of the helper: on timeout the worker is interrupted instead of running on. */
    @Test
    void aTimeoutInterruptsTheWork() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        CompletableFuture<String> future = SearchTasks.supply(pool, 100, () -> {
            try {
                Thread.sleep(10_000);
                return "late";
            } catch (InterruptedException e) {
                interrupted.countDown();
                return "interrupted";
            }
        });

        assertThatThrownBy(future::join).hasCauseInstanceOf(TimeoutException.class);
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).as("worker interrupted").isTrue();
    }

    @Test
    void aFullExecutorRejectsSynchronously() {
        ThreadPoolExecutor full = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        CountDownLatch release = new CountDownLatch(1);
        try {
            SearchTasks.supply(full, 5_000, () -> await(release));
            SearchTasks.supply(full, 5_000, () -> await(release));

            assertThatThrownBy(() -> SearchTasks.supply(full, 5_000, () -> "third"))
                    .isInstanceOf(RejectedExecutionException.class);
        } finally {
            release.countDown();
            full.shutdownNow();
        }
    }

    private static String await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "done";
    }
}
