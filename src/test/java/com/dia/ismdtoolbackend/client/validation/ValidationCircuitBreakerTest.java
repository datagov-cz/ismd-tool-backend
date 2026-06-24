package com.dia.ismdtoolbackend.client.validation;

import com.dia.ismdtoolbackend.exception.ValidationServiceUnavailableException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValidationCircuitBreakerTest {

    private ValidationServiceUnavailableException down() {
        return new ValidationServiceUnavailableException("Validační služba", "down");
    }

    @Test
    void startsClosed() {
        ValidationCircuitBreaker breaker = new ValidationCircuitBreaker("v", 2, 30_000);
        assertFalse(breaker.isOpen());
        assertEquals("ok", breaker.call(() -> "ok"));
        assertFalse(breaker.isOpen());
    }

    @Test
    void opensAfterThreshold() {
        ValidationCircuitBreaker breaker = new ValidationCircuitBreaker("v", 2, 30_000);

        assertThrows(ValidationServiceUnavailableException.class, () -> breaker.call(() -> { throw down(); }));
        assertFalse(breaker.isOpen(), "one failure below threshold — still closed");

        assertThrows(ValidationServiceUnavailableException.class, () -> breaker.call(() -> { throw down(); }));
        assertTrue(breaker.isOpen(), "second failure hits threshold — now open");
    }

    @Test
    void openBreakerFastFailsWithoutCallingAction() {
        ValidationCircuitBreaker breaker = new ValidationCircuitBreaker("v", 1, 30_000);
        assertThrows(ValidationServiceUnavailableException.class, () -> breaker.call(() -> { throw down(); }));
        assertTrue(breaker.isOpen());

        boolean[] ran = {false};
        assertThrows(ValidationServiceUnavailableException.class,
                () -> breaker.call(() -> { ran[0] = true; return "x"; }));
        assertFalse(ran[0], "open breaker must not invoke the action");
    }

    @Test
    void halfOpenTrialClosesOnSuccess() {
        // Zero cooldown → the breaker is immediately past cooldown, so the next call is a trial.
        ValidationCircuitBreaker breaker = new ValidationCircuitBreaker("v", 1, 0);
        assertThrows(ValidationServiceUnavailableException.class, () -> breaker.call(() -> { throw down(); }));
        // cooldown is 0 → not "open" by the time check; a successful trial closes it.
        assertEquals("ok", breaker.call(() -> "ok"));
        assertFalse(breaker.isOpen());
    }

    @Test
    void rejectionStyleExceptionsDoNotTrip() {
        ValidationCircuitBreaker breaker = new ValidationCircuitBreaker("v", 1, 30_000);
        // A non-unavailable exception propagates and does NOT count toward opening.
        assertThrows(IllegalStateException.class, () -> breaker.call(() -> { throw new IllegalStateException("4xx"); }));
        assertFalse(breaker.isOpen());
    }
}
