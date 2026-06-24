package com.dia.ismdtoolbackend.client.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Pulls a readable reason out of the validator's 4xx error body so the tool can surface
 * the validator's <em>own</em> message instead of a generic rejection.
 *
 * <p>The validator returns a structured body {@code {error, message, timestamp, requestId}}.
 * We read the {@code message} field loosely (no shared type yet) and <strong>fall back to the
 * raw body string</strong> when the body is absent or not the expected shape.
 *
 * <p>The shared {@code com.dia.validation.ValidatorErrorResponse} (added on the validator's feat
 * branch) has the same {@code message} field this reads, so this works unchanged once that lands
 * in a released {@code ismd-validator-common}. The optional follow-up is to deserialize that type
 * directly instead of reading the {@code message} node loosely; the raw-body fallback keeps the
 * tool independent of the release either way.
 */
@Slf4j
class ValidatorErrorMessageExtractor {

    private static final String FALLBACK = "Validační služba odmítla požadavek.";

    private final ObjectMapper objectMapper = new ObjectMapper();

    String extract(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        if (body.isBlank()) {
            return FALLBACK;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode message = root.get("message");
            if (message != null && message.isTextual() && !message.asText().isBlank()) {
                return message.asText();
            }
        } catch (Exception parseError) {
            log.debug("Validator error body was not the expected JSON shape; using raw body. {}",
                    parseError.getMessage());
        }
        return body;
    }
}
