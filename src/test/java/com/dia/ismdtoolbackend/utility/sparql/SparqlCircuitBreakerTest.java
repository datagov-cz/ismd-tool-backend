package com.dia.ismdtoolbackend.utility.sparql;

import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.testsupport.time.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

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
            assertThat(out).isEqualTo("ok");
        }
        assertThat(calls.get()).isEqualTo(10);
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    void breakerOpensAfterThresholdFailures() {
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> breaker.call(() -> {
                calls.incrementAndGet();
                throw new SparqlEndpointUnavailableException("test", "boom");
            }))
                    .isInstanceOf(SparqlEndpointUnavailableException.class);
        }
        assertThat(breaker.isOpen()).as("breaker should be open after threshold consecutive failures").isTrue();

        // Subsequent call should fast-fail without invoking the action
        int callsBefore = calls.get();
        SparqlEndpointUnavailableException ex = assertThatExceptionOfType(SparqlEndpointUnavailableException.class)
                .isThrownBy(() -> breaker.call(() -> {
                    calls.incrementAndGet();
                    return "should not run";
                }))
                .actual();
        assertThat(ex).hasMessageContaining("circuit breaker open");
        assertThat(calls.get()).as("open breaker must not invoke action").isEqualTo(callsBefore);
    }

    @Test
    void successResetsConsecutiveFailureCounter() {
        // 2 failures
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> breaker.call(() -> { throw new SparqlEndpointUnavailableException("test", "boom"); }))
                    .isInstanceOf(SparqlEndpointUnavailableException.class);
        }
        // success — resets
        breaker.call(() -> "ok");
        assertThat(breaker.isOpen()).isFalse();

        // 2 more failures — should NOT open since counter reset
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> breaker.call(() -> { throw new SparqlEndpointUnavailableException("test", "boom"); }))
                    .isInstanceOf(SparqlEndpointUnavailableException.class);
        }
        assertThat(breaker.isOpen())
                .as("2 failures after reset should NOT open the breaker (threshold is 3)")
                .isFalse();
    }

    @Test
    void breakerHalfOpensAfterCooldownAndClosesOnSuccess() {
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> breaker.call(() -> { throw new SparqlEndpointUnavailableException("test", "boom"); }))
                    .isInstanceOf(SparqlEndpointUnavailableException.class);
        }
        assertThat(breaker.isOpen()).isTrue();

        clock.advanceMillis(COOLDOWN_MS + 1);

        // Trial call succeeds — breaker closes
        String out = breaker.call(() -> "ok");
        assertThat(out).isEqualTo("ok");
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    void breakerHalfOpensAfterCooldownAndReopensOnFailure() {
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> breaker.call(() -> { throw new SparqlEndpointUnavailableException("test", "boom"); }))
                    .isInstanceOf(SparqlEndpointUnavailableException.class);
        }
        clock.advanceMillis(COOLDOWN_MS + 1);

        // Trial call fails — breaker re-opens
        assertThatThrownBy(() -> breaker.call(() -> { throw new SparqlEndpointUnavailableException("test", "still down"); }))
                .isInstanceOf(SparqlEndpointUnavailableException.class);
        assertThat(breaker.isOpen()).isTrue();

        clock.advanceMillis(COOLDOWN_MS - 1);
        assertThat(breaker.isOpen()).as("the new cooldown window counts from the failed trial").isTrue();
    }

    @Test
    void staysOpenUntilTheLastMillisecondOfTheCooldown() {
        tripBreaker();

        clock.advanceMillis(COOLDOWN_MS - 1);

        assertThat(breaker.isOpen()).isTrue();
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> breaker.call(() -> calls.incrementAndGet()))
                .isInstanceOf(SparqlEndpointUnavailableException.class);
        assertThat(calls.get())
                .as("one millisecond before the cooldown ends the breaker still fast-fails")
                .isEqualTo(0);

        clock.advanceMillis(1);

        assertThat(breaker.isOpen()).as("the cooldown has elapsed exactly").isFalse();
        assertThat(breaker.call(() -> "ok")).as("the call at the cooldown boundary is the trial").isEqualTo("ok");
    }

    @Test
    void publicConstructorUsesTheSystemClock() {
        SparqlCircuitBreaker systemClocked = new SparqlCircuitBreaker("test", 1, COOLDOWN_MS);
        assertThatThrownBy(() -> systemClocked.call(() -> { throw new SparqlEndpointUnavailableException("test", "boom"); }))
                .isInstanceOf(SparqlEndpointUnavailableException.class);
        assertThat(systemClocked.isOpen()).as("a 30 s cooldown has not elapsed on the real clock").isTrue();
    }

    @Test
    void nonSparqlExceptionsDoNotTripTheBreaker() {
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> breaker.call(() -> { throw new IllegalArgumentException("user input"); }))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(breaker.isOpen()).as("non-SPARQL exceptions are user/domain errors and must not count").isFalse();
    }

    private void tripBreaker() {
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> breaker.call(() -> { throw new SparqlEndpointUnavailableException("test", "boom"); }))
                    .isInstanceOf(SparqlEndpointUnavailableException.class);
        }
    }
}
