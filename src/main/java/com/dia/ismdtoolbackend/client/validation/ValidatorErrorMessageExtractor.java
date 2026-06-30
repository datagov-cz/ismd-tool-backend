package com.dia.ismdtoolbackend.client.validation;

import com.dia.validation.ValidatorErrorResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Pulls a readable reason out of the validator's 4xx error body so the tool can surface
 * the validator's <em>own</em> message instead of a generic rejection.
 *
 * <p>The validator returns the shared {@link ValidatorErrorResponse} body
 * ({@code {error, message, timestamp, requestId}}). We deserialize that type and read its
 * {@code message}, falling back to the <strong>raw body string</strong> when the body is absent,
 * not that shape (e.g. an HTML page from a reverse proxy), or carries no usable message.
 *
 * <p>The raw-body fallback keeps the tool independent of the validator's release: even if the
 * error contract drifts, the caller still gets the original body rather than a crash.
 */
@Slf4j
class ValidatorErrorMessageExtractor {

    private static final String FALLBACK = "Validační služba odmítla požadavek.";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    String extract(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        if (body.isBlank()) {
            return FALLBACK;
        }
        try {
            ValidatorErrorResponse parsed = objectMapper.readValue(body, ValidatorErrorResponse.class);
            String message = parsed.getMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
        } catch (Exception parseError) {
            log.debug("Validator error body was not the expected JSON shape; using raw body. {}",
                    parseError.getMessage());
        }
        return body;
    }
}
