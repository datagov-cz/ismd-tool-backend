package com.dia.ismdtoolbackend.publication.file;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderedFetcherTest {

    private static final List<Integer> ITEMS = IntStream.range(0, 20).boxed().toList();

    @Test
    void consumesInListOrder_withAtMostParallelismResultsAlive() {
        AtomicInteger alive = new AtomicInteger();
        AtomicInteger maxAlive = new AtomicInteger();
        List<Integer> consumed = new ArrayList<>();

        OrderedFetcher.forEachInOrder(ITEMS, 3, "test-", item -> {
            sleep((20 - item) * 3L);
            maxAlive.accumulateAndGet(alive.incrementAndGet(), Math::max);
            return item;
        }, item -> {
            consumed.add(item);
            alive.decrementAndGet();
        });

        assertThat(consumed).containsExactlyElementsOf(ITEMS);
        assertThat(maxAlive.get()).isBetween(2, 3);
    }

    @Test
    void firstFailureInListOrder_isRethrown() {
        assertThatThrownBy(() -> OrderedFetcher.forEachInOrder(ITEMS, 4, "test-", item -> {
            if (item == 7) {
                throw new IllegalStateException("item " + item);
            }
            return item;
        }, item -> { })).isInstanceOf(IllegalStateException.class).hasMessage("item 7");
    }

    @Test
    void noItems_startsNothing() {
        OrderedFetcher.forEachInOrder(List.<Integer>of(), 4, "test-", item -> {
            throw new AssertionError("not called");
        }, item -> { });
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
