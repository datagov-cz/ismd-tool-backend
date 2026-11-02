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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

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
        assertThat(exec.strict("t", () -> "ok")).isEqualTo("ok");
    }

    @Test
    void strict_resourceAccess_mapsToUnavailable() {
        ValidationCallExecutor exec = executor(1000, Duration.ofSeconds(30));
        assertThatThrownBy(() -> exec.strict("t", () -> { throw new ResourceAccessException("timeout"); }))
                .isInstanceOf(ValidationServiceUnavailableException.class);
    }

    @Test
    void strict_5xx_mapsToUnavailable() {
        ValidationCallExecutor exec = executor(1000, Duration.ofSeconds(30));
        assertThatThrownBy(() -> exec.strict("t", () -> {
                    throw HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "x", null, null, null);
                }))
                .isInstanceOf(ValidationServiceUnavailableException.class);
    }

    @Test
    void strict_4xx_mapsToRejection_withValidatorMessage() {
        ValidationCallExecutor exec = executor(1000, Duration.ofSeconds(30));
        String body = "{\"message\":\"Invalid TTL syntax: foo\"}";
        OntologyValidationException ex = assertThatExceptionOfType(OntologyValidationException.class)
                .isThrownBy(() -> exec.strict("t", () -> {
                    throw HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                            null, body.getBytes(), null);
                }))
                .actual();
        assertThat(ex).hasMessageContaining("Invalid TTL syntax: foo");
    }

    @Test
    void breaker_opensAfterThreshold_thenFastFailsWithoutCallingAction() {
        ValidationCallExecutor exec = executor(2, Duration.ofSeconds(30));
        AtomicInteger calls = new AtomicInteger();

        // Two unavailable failures trip the breaker.
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> exec.strict("t", () -> {
                        calls.incrementAndGet();
                        throw new ResourceAccessException("down");
                    }))
                    .isInstanceOf(ValidationServiceUnavailableException.class);
        }
        assertThat(calls.get()).isEqualTo(2);

        // Breaker now open — the action must NOT be invoked.
        assertThatThrownBy(() -> exec.strict("t", () -> {
                    calls.incrementAndGet();
                    return "should not run";
                }))
                .isInstanceOf(ValidationServiceUnavailableException.class);
        assertThat(calls.get()).as("open breaker must fast-fail without calling the action").isEqualTo(2);
    }

    @Test
    void breaker_rejectionDoesNotCountTowardOpening() {
        ValidationCallExecutor exec = executor(2, Duration.ofSeconds(30));
        AtomicInteger calls = new AtomicInteger();

        // Many 4xx rejections — the validator is UP, so the breaker must never open.
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> exec.strict("t", () -> {
                        calls.incrementAndGet();
                        throw HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "x",
                                null, "{\"message\":\"bad\"}".getBytes(), null);
                    }))
                    .isInstanceOf(OntologyValidationException.class);
        }
        assertThat(calls.get()).as("every rejection call should reach the action (breaker stays closed)").isEqualTo(5);
    }

    @Test
    void lenient_swallowsUnavailable_returnsFallback() {
        ValidationCallExecutor exec = executor(1000, Duration.ofSeconds(30));
        String result = exec.lenient("t",
                () -> { throw new ResourceAccessException("down"); }, "fallback");
        assertThat(result).isEqualTo("fallback");
    }

    @Test
    void lenient_swallowsRejection_returnsFallback() {
        ValidationCallExecutor exec = executor(1000, Duration.ofSeconds(30));
        String result = exec.lenient("t",
                () -> { throw HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "x",
                        null, "{\"message\":\"bad\"}".getBytes(), null); }, "fallback");
        assertThat(result).isEqualTo("fallback");
    }
}
