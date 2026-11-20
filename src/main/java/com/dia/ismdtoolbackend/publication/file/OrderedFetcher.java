package com.dia.ismdtoolbackend.publication.file;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Fetches items concurrently and hands the results over strictly in list order. At most
 * {@code parallelism} results exist at a time, the one being consumed included, so memory stays flat in
 * the number of items.
 */
final class OrderedFetcher {

    private OrderedFetcher() {
    }

    /**
     * @throws RuntimeException the first failure of {@code fetch} or {@code consume}, in list order;
     *                          fetches still running are interrupted
     */
    static <T, R> void forEachInOrder(List<T> items, int parallelism, String threadPrefix,
                                      Function<T, R> fetch, Consumer<R> consume) {
        if (items.isEmpty()) {
            return;
        }
        int threads = Math.max(1, Math.min(parallelism, items.size()));
        AtomicInteger counter = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, threadPrefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        try {
            Deque<Future<R>> window = new ArrayDeque<>(threads);
            int next = 0;
            while (next < items.size() && window.size() < threads) {
                window.add(submit(pool, fetch, items.get(next++)));
            }
            while (!window.isEmpty()) {
                consume.accept(await(window.poll()));
                if (next < items.size()) {
                    window.add(submit(pool, fetch, items.get(next++)));
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static <T, R> Future<R> submit(ExecutorService pool, Function<T, R> fetch, T item) {
        return pool.submit(() -> fetch.apply(item));
    }

    private static <R> R await(Future<R> result) {
        try {
            return result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a fetch", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }
}
