package com.dia.ismdtoolbackend.client.validation;

import com.dia.ismdtoolbackend.config.ValidationServiceConfig;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.exception.ValidationServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ValidationCallExecutorTest {

    private ValidationCallExecutor executor(int threshold, Duration cooldown) {
        ValidationServiceConfig config = new ValidationServiceConfig();
        config.getBreaker().setFailureThreshold(threshold);
        config.getBreaker().setCooldown(cooldown);
        return new ValidationCallExecutor(config);
    }

    @Test
    void strict_success_returnsValue() {
        ValidationCallExecutor exec = executor(5, Duration.ofSeconds(30));
        assertEquals("ok", exec.strict("t", () -> "ok"));
    }

    @Test
    void strict_resourceAccess_mapsToUnavailable() {
        ValidationCallExecutor exec = executor(1000, Duration.ofSeconds(30));
        assertThrows(ValidationServiceUnavailableException.class,
                () -> exec.strict("t", () -> { throw new ResourceAccessException("timeout"); }));
    }

    @Test
    void strict_5xx_mapsToUnavailable() {
        ValidationCallExecutor exec = executor(1000, Duration.ofSeconds(30));
        assertThrows(ValidationServiceUnavailableException.class,
                () -> exec.strict("t", () -> {
                    throw HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "x", null, null, null);
                }));
    }

    @Test
    void strict_4xx_mapsToRejection_withValidatorMessage() {
        ValidationCallExecutor exec = executor(1000, Duration.ofSeconds(30));
        String body = "{\"message\":\"Invalid TTL syntax: foo\"}";
        OntologyValidationException ex = assertThrows(OntologyValidationException.class,
                () -> exec.strict("t", () -> {
                    throw HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                            null, body.getBytes(), null);
                }));
        assertTrue(ex.getMessage().contains("Invalid TTL syntax: foo"));
    }

    @Test
    void breaker_opensAfterThreshold_thenFastFailsWithoutCallingAction() {
        ValidationCallExecutor exec = executor(2, Duration.ofSeconds(30));
        AtomicInteger calls = new AtomicInteger();

        // Two unavailable failures trip the breaker.
        for (int i = 0; i < 2; i++) {
            assertThrows(ValidationServiceUnavailableException.class,
                    () -> exec.strict("t", () -> {
                        calls.incrementAndGet();
                        throw new ResourceAccessException("down");
                    }));
        }
        assertEquals(2, calls.get());

        // Breaker now open — the action must NOT be invoked.
        assertThrows(ValidationServiceUnavailableException.class,
                () -> exec.strict("t", () -> {
                    calls.incrementAndGet();
                    return "should not run";
                }));
        assertEquals(2, calls.get(), "open breaker must fast-fail without calling the action");
    }

    @Test
    void breaker_rejectionDoesNotCountTowardOpening() {
        ValidationCallExecutor exec = executor(2, Duration.ofSeconds(30));
        AtomicInteger calls = new AtomicInteger();

        // Many 4xx rejections — the validator is UP, so the breaker must never open.
        for (int i = 0; i < 5; i++) {
            assertThrows(OntologyValidationException.class,
                    () -> exec.strict("t", () -> {
                        calls.incrementAndGet();
                        throw HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "x",
                                null, "{\"message\":\"bad\"}".getBytes(), null);
                    }));
        }
        assertEquals(5, calls.get(), "every rejection call should reach the action (breaker stays closed)");
    }

    @Test
    void lenient_swallowsUnavailable_returnsFallback() {
        ValidationCallExecutor exec = executor(1000, Duration.ofSeconds(30));
        String result = exec.lenient("t",
                () -> { throw new ResourceAccessException("down"); }, "fallback");
        assertEquals("fallback", result);
    }

    @Test
    void lenient_swallowsRejection_returnsFallback() {
        ValidationCallExecutor exec = executor(1000, Duration.ofSeconds(30));
        String result = exec.lenient("t",
                () -> { throw HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "x",
                        null, "{\"message\":\"bad\"}".getBytes(), null); }, "fallback");
        assertEquals("fallback", result);
    }
}
