package com.dia.ismdtoolbackend.utility.sparql;

import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.testsupport.time.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparqlCircuitBreakerTest {

    private static final long COOLDOWN_MS = 30_000;

    private final MutableClock clock = MutableClock.of("2026-10-21T10:00:00Z");
    private SparqlCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = new SparqlCircuitBreaker("test", 3, COOLDOWN_MS, clock);
    }

    @Test
    void closedBreakerLetsCallsThrough() {
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            String out = breaker.call(() -> {
                calls.incrementAndGet();
                return "ok";
            });
            assertEquals("ok", out);
        }
        assertEquals(10, calls.get());
        assertFalse(breaker.isOpen());
    }

    @Test
    void breakerOpensAfterThresholdFailures() {
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            assertThrows(SparqlEndpointUnavailableException.class, () -> breaker.call(() -> {
                calls.incrementAndGet();
                throw new SparqlEndpointUnavailableException("test", "boom");
            }));
        }
        assertTrue(breaker.isOpen(), "breaker should be open after threshold consecutive failures");

        // Subsequent call should fast-fail without invoking the action
        int callsBefore = calls.get();
        SparqlEndpointUnavailableException ex = assertThrows(SparqlEndpointUnavailableException.class,
                () -> breaker.call(() -> {
                    calls.incrementAndGet();
                    return "should not run";
                }));
        assertTrue(ex.getMessage().contains("circuit breaker open"));
        assertEquals(callsBefore, calls.get(), "open breaker must not invoke action");
    }

    @Test
    void successResetsConsecutiveFailureCounter() {
        // 2 failures
        for (int i = 0; i < 2; i++) {
            assertThrows(SparqlEndpointUnavailableException.class,
                    () -> breaker.call(() -> { throw new SparqlEndpointUnavailableException("test", "boom"); }));
        }
        // success — resets
        breaker.call(() -> "ok");
        assertFalse(breaker.isOpen());

        // 2 more failures — should NOT open since counter reset
        for (int i = 0; i < 2; i++) {
            assertThrows(SparqlEndpointUnavailableException.class,
                    () -> breaker.call(() -> { throw new SparqlEndpointUnavailableException("test", "boom"); }));
        }
        assertFalse(breaker.isOpen(), "2 failures after reset should NOT open the breaker (threshold is 3)");
    }

    @Test
    void breakerHalfOpensAfterCooldownAndClosesOnSuccess() {
        for (int i = 0; i < 3; i++) {
            assertThrows(SparqlEndpointUnavailableException.class,
                    () -> breaker.call(() -> { throw new SparqlEndpointUnavailableException("test", "boom"); }));
        }
        assertTrue(breaker.isOpen());

        clock.advanceMillis(COOLDOWN_MS + 1);

        // Trial call succeeds — breaker closes
        String out = breaker.call(() -> "ok");
        assertEquals("ok", out);
        assertFalse(breaker.isOpen());
    }

    @Test
    void breakerHalfOpensAfterCooldownAndReopensOnFailure() {
        for (int i = 0; i < 3; i++) {
            assertThrows(SparqlEndpointUnavailableException.class,
                    () -> breaker.call(() -> { throw new SparqlEndpointUnavailableException("test", "boom"); }));
        }
        clock.advanceMillis(COOLDOWN_MS + 1);

        // Trial call fails — breaker re-opens
        assertThrows(SparqlEndpointUnavailableException.class,
                () -> breaker.call(() -> { throw new SparqlEndpointUnavailableException("test", "still down"); }));
        assertTrue(breaker.isOpen());

        clock.advanceMillis(COOLDOWN_MS - 1);
        assertTrue(breaker.isOpen(), "the new cooldown window counts from the failed trial");
    }

    @Test
    void staysOpenUntilTheLastMillisecondOfTheCooldown() {
        tripBreaker();

        clock.advanceMillis(COOLDOWN_MS - 1);

        assertTrue(breaker.isOpen());
        AtomicInteger calls = new AtomicInteger();
        assertThrows(SparqlEndpointUnavailableException.class,
                () -> breaker.call(() -> calls.incrementAndGet()));
        assertEquals(0, calls.get(), "one millisecond before the cooldown ends the breaker still fast-fails");

        clock.advanceMillis(1);

        assertFalse(breaker.isOpen(), "the cooldown has elapsed exactly");
        assertEquals("ok", breaker.call(() -> "ok"), "the call at the cooldown boundary is the trial");
    }

    @Test
    void publicConstructorUsesTheSystemClock() {
        SparqlCircuitBreaker systemClocked = new SparqlCircuitBreaker("test", 1, COOLDOWN_MS);
        assertThrows(SparqlEndpointUnavailableException.class,
                () -> systemClocked.call(() -> { throw new SparqlEndpointUnavailableException("test", "boom"); }));
        assertTrue(systemClocked.isOpen(), "a 30 s cooldown has not elapsed on the real clock");
    }

    @Test
    void nonSparqlExceptionsDoNotTripTheBreaker() {
        for (int i = 0; i < 10; i++) {
            assertThrows(IllegalArgumentException.class,
                    () -> breaker.call(() -> { throw new IllegalArgumentException("user input"); }));
        }
        assertFalse(breaker.isOpen(), "non-SPARQL exceptions are user/domain errors and must not count");
    }

    private void tripBreaker() {
        for (int i = 0; i < 3; i++) {
            assertThrows(SparqlEndpointUnavailableException.class,
                    () -> breaker.call(() -> { throw new SparqlEndpointUnavailableException("test", "boom"); }));
        }
    }
}
