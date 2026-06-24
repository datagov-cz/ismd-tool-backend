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
    void halfOpenTrialClosesOnSuccess() throws InterruptedException {
        // Small but non-zero cooldown so the breaker is genuinely OPEN, then expires into a trial.
        ValidationCircuitBreaker breaker = new ValidationCircuitBreaker("v", 1, 50);
        assertThrows(ValidationServiceUnavailableException.class, () -> breaker.call(() -> { throw down(); }));
        assertTrue(breaker.isOpen(), "breaker is open during cooldown");

        // While open, a call fast-fails without running the action.
        boolean[] ran = {false};
        assertThrows(ValidationServiceUnavailableException.class,
                () -> breaker.call(() -> { ran[0] = true; return "x"; }));
        assertFalse(ran[0], "open breaker fast-fails");

        Thread.sleep(70); // past cooldown → next call is the half-open trial
        assertEquals("ok", breaker.call(() -> "ok"), "trial call runs and succeeds");
        assertFalse(breaker.isOpen(), "successful trial closes the breaker");
    }

    @Test
    void halfOpenTrialFailureReopens() throws InterruptedException {
        ValidationCircuitBreaker breaker = new ValidationCircuitBreaker("v", 1, 50);
        assertThrows(ValidationServiceUnavailableException.class, () -> breaker.call(() -> { throw down(); }));
        assertTrue(breaker.isOpen());

        Thread.sleep(70); // past cooldown → trial allowed
        // Trial fails → breaker re-opens for another cooldown window.
        boolean[] ran = {false};
        assertThrows(ValidationServiceUnavailableException.class,
                () -> breaker.call(() -> { ran[0] = true; throw down(); }));
        assertTrue(ran[0], "the trial call actually ran");
        assertTrue(breaker.isOpen(), "failed trial re-opens the breaker");
    }

    @Test
    void rejectionStyleExceptionsDoNotTrip() {
        ValidationCircuitBreaker breaker = new ValidationCircuitBreaker("v", 1, 30_000);
        // A non-unavailable exception propagates and does NOT count toward opening.
        assertThrows(IllegalStateException.class, () -> breaker.call(() -> { throw new IllegalStateException("4xx"); }));
        assertFalse(breaker.isOpen());
    }
}
