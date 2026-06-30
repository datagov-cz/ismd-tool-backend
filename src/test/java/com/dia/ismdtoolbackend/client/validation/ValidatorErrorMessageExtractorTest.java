package com.dia.ismdtoolbackend.client.validation;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ValidatorErrorMessageExtractorTest {

    private final ValidatorErrorMessageExtractor extractor = new ValidatorErrorMessageExtractor();

    private HttpClientErrorException with(String body) {
        return HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                null, body == null ? null : body.getBytes(StandardCharsets.UTF_8), null);
    }

    @Test
    void structuredBody_extractsMessageField() {
        String result = extractor.extract(with("{\"error\":\"Validation Error\",\"message\":\"Invalid TTL syntax: line 3\"}"));
        assertEquals("Invalid TTL syntax: line 3", result);
    }

    @Test
    void blankBody_returnsFallback() {
        String result = extractor.extract(with(""));
        assertEquals("Validační služba odmítla požadavek.", result);
    }

    @Test
    void malformedJson_fallsBackToRawBody() {
        String raw = "this is not json at all";
        assertEquals(raw, extractor.extract(with(raw)));
    }

    @Test
    void jsonWithoutMessageField_fallsBackToRawBody() {
        String raw = "{\"error\":\"X\",\"detail\":\"no message field here\"}";
        assertEquals(raw, extractor.extract(with(raw)));
    }

    @Test
    void jsonWithBlankMessage_fallsBackToRawBody() {
        String raw = "{\"message\":\"   \"}";
        assertEquals(raw, extractor.extract(with(raw)));
    }

    @Test
    void htmlErrorPage_fallsBackToRawBody() {
        // A reverse proxy returning an HTML 400 must not crash the extractor.
        String raw = "<html><body>400 Bad Request</body></html>";
        assertEquals(raw, extractor.extract(with(raw)));
    }
}
