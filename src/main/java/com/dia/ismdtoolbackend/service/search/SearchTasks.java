package com.dia.ismdtoolbackend.service.search;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Runs a search step on an executor with a timeout that also stops the work. {@code orTimeout} alone only
 * releases the caller: the task keeps its thread and its connection. Here a timed-out task is interrupted,
 * which aborts a waiting Fuseki HTTP call; PG queries carry their own statement timeout.
 *
 * <p>A full executor rejects synchronously ({@code RejectedExecutionException}), before any future exists.
 */
public final class SearchTasks {

    private SearchTasks() {
    }

    public static <T> CompletableFuture<T> supply(Executor executor, long timeoutMs, Supplier<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                result.complete(work.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        }, null);
        executor.execute(task);
        result.orTimeout(timeoutMs, TimeUnit.MILLISECONDS).whenComplete((value, failure) -> {
            if (failure instanceof TimeoutException) {
                task.cancel(true);
            }
        });
        return result;
    }
}
