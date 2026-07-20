package com.dia.ismdtoolbackend.client.validation;

import com.dia.ismdtoolbackend.config.ValidationServiceConfig;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.exception.ValidationServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.util.function.Supplier;

/**
 * Single call path for the external validator. Wraps a {@link Supplier} with a circuit breaker and
 * classifies failures into two distinct outcomes:
 *
 * <ul>
 *   <li><strong>Unavailable</strong> — connect/read timeout, connection refused, 5xx, or an
 *       otherwise unusable response → {@link ValidationServiceUnavailableException} (HTTP 503),
 *       counted by the breaker. The validator is down or broken.</li>
 *   <li><strong>Rejected</strong> — the validator answered with a 4xx (e.g. malformed TTL) →
 *       {@link OntologyValidationException} (HTTP 400) carrying the validator's own message.</li>
 * </ul>
 *
 * <p>{@link #strict} propagates these typed exceptions (used by the blocking {@code /validate}
 * endpoint). {@link #lenient} swallows any failure and returns a fallback after logging (used
 * by the advisory upload path, which must never block ingest).
 */
@Slf4j
@Component
public class ValidationCallExecutor {

    private static final String ENDPOINT_LABEL = "Validační služba";

    private final ValidationCircuitBreaker breaker;
    private final ValidatorErrorMessageExtractor errorExtractor;

    public ValidationCallExecutor(ValidationServiceConfig config) {
        this.breaker = new ValidationCircuitBreaker(
                ENDPOINT_LABEL,
                config.getBreaker().getFailureThreshold(),
                config.getBreaker().getCooldown().toMillis());
        this.errorExtractor = new ValidatorErrorMessageExtractor();
    }

    /**
     * Run {@code action} through the breaker, mapping low-level failures to typed exceptions.
     * A 4xx from the validator becomes a 400 ({@link OntologyValidationException}); anything
     * indicating the validator is down/unusable becomes a 503
     * ({@link ValidationServiceUnavailableException}).
     */
    public <T> T strict(String label, Supplier<T> action) {
        return breaker.call(() -> {
            try {
                return action.get();
            } catch (HttpClientErrorException e) {
                String message = errorExtractor.extract(e);
                log.warn("Validator rejected {}: {}", label, message);
                throw new OntologyValidationException(message);
            } catch (HttpServerErrorException e) {
                throw unavailable(label + " — validator returned " + e.getStatusCode(), e);
            } catch (ResourceAccessException e) {
                throw unavailable(label + " — validator unreachable: " + e.getMessage(), e);
            } catch (RestClientException e) {
                throw unavailable(label + " — validator call failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Run {@code action}; on ANY failure log a warning and return {@code fallback}. The breaker
     * still observes unavailability (a swallowed outage still trips it for the strict path), but
     * the caller never sees an exception. For the advisory upload path.
     */
    public <T> T lenient(String label, Supplier<T> action, T fallback) {
        try {
            return strict(label, action);
        } catch (OntologyValidationException e) {
            log.warn("Validator rejected {} (advisory, ignored): {}", label, e.getMessage());
            return fallback;
        } catch (ValidationServiceUnavailableException e) {
            log.warn("Validator unavailable for {} (advisory, ignored): {}", label, e.getMessage());
            return fallback;
        }
    }

    private ValidationServiceUnavailableException unavailable(String message, Throwable cause) {
        return new ValidationServiceUnavailableException(ENDPOINT_LABEL, message, cause);
    }
}
