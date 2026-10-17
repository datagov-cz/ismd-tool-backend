package com.dia.ismdtoolbackend.client.esbirka;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsbirkaRateGateTest {

    private static final long SPACING_MS = 30;
    private static final int MAX_IN_FLIGHT = 3;
    private static final int CALLERS = 12;

    @Test
    void concurrentCallersDepartSpacedAndWithinTheInFlightCap() throws Exception {
        EsbirkaRateGate gate = new EsbirkaRateGate(SPACING_MS, MAX_IN_FLIGHT);
        List<Long> departures = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CALLERS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < CALLERS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    departures.add(gate.acquire(EsbirkaDeadline.after(Duration.ofSeconds(10))));
                    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(100); // the "call": longer than the spacing, so the cap is reached
                    } finally {
                        inFlight.decrementAndGet();
                        gate.release();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(CALLERS, departures.size());
        List<Long> sorted = new ArrayList<>(departures);
        Collections.sort(sorted);
        for (int i = 1; i < sorted.size(); i++) {
            long gapMs = TimeUnit.NANOSECONDS.toMillis(sorted.get(i) - sorted.get(i - 1));
            assertTrue(gapMs >= SPACING_MS, "departures " + (i - 1) + "→" + i + " only " + gapMs + " ms apart");
        }
        assertEquals(MAX_IN_FLIGHT, maxInFlight.get(), "in-flight cap must be reached and never exceeded");
    }

    @Test
    void deadlineBeforeTheNextSlotFailsWithoutWaiting() throws Exception {
        EsbirkaRateGate gate = new EsbirkaRateGate(5_000, 4);
        gate.acquire(EsbirkaDeadline.after(Duration.ofSeconds(1)));
        gate.release();

        long t0 = System.nanoTime();
        assertThrows(EsbirkaRateGate.DeadlineExceeded.class,
                () -> gate.acquire(EsbirkaDeadline.after(Duration.ofMillis(200))));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 1_000);
    }

    @Test
    void deadlineWhileAllSlotsAreInFlightFails() throws Exception {
        EsbirkaRateGate gate = new EsbirkaRateGate(0, 1);
        gate.acquire(EsbirkaDeadline.after(Duration.ofSeconds(1)));

        long t0 = System.nanoTime();
        assertThrows(EsbirkaRateGate.DeadlineExceeded.class,
                () -> gate.acquire(EsbirkaDeadline.after(Duration.ofMillis(150))));
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(waitedMs >= 100 && waitedMs < 1_000, "waited " + waitedMs + " ms");

        gate.release();
        gate.acquire(EsbirkaDeadline.after(Duration.ofMillis(150)));
    }
}
