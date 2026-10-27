package com.dia.ismdtoolbackend.client.validation;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ValidatorErrorMessageExtractorTest {

    private final ValidatorErrorMessageExtractor extractor = new ValidatorErrorMessageExtractor();

    private HttpClientErrorException with(String body) {
        return HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                null, body == null ? null : body.getBytes(StandardCharsets.UTF_8), null);
    }

    @Test
    void structuredBody_extractsMessageField() {
        String result = extractor.extract(with("{\"error\":\"Validation Error\",\"message\":\"Invalid TTL syntax: line 3\"}"));
        assertThat(result).isEqualTo("Invalid TTL syntax: line 3");
    }

    @Test
    void blankBody_returnsFallback() {
        String result = extractor.extract(with(""));
        assertThat(result).isEqualTo("Validační služba odmítla požadavek.");
    }

    @Test
    void malformedJson_fallsBackToRawBody() {
        String raw = "this is not json at all";
        assertThat(extractor.extract(with(raw))).isEqualTo(raw);
    }

    @Test
    void jsonWithoutMessageField_fallsBackToRawBody() {
        String raw = "{\"error\":\"X\",\"detail\":\"no message field here\"}";
        assertThat(extractor.extract(with(raw))).isEqualTo(raw);
    }

    @Test
    void jsonWithBlankMessage_fallsBackToRawBody() {
        String raw = "{\"message\":\"   \"}";
        assertThat(extractor.extract(with(raw))).isEqualTo(raw);
    }

    @Test
    void htmlErrorPage_fallsBackToRawBody() {
        // A reverse proxy returning an HTML 400 must not crash the extractor.
        String raw = "<html><body>400 Bad Request</body></html>";
        assertThat(extractor.extract(with(raw))).isEqualTo(raw);
    }
}
