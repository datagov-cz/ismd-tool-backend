package com.dia.ismdtoolbackend.client.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Reads the validator's response bodies through a plain {@link RestClient} — the same default JSON
 * converters as the production {@code validationRestClient}. The fixtures in {@code contract/*.json}
 * are this repo's own copy of what the validator sends; if the validator changes its API, update
 * them and this test together.
 */
class ValidatorContractTest {

    private static final String URL = "http://validator/validujeme/validate";

    private MockRestServiceServer server;
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
    }

    @Test
    void validationReport_isReadIntoToolTypes() throws IOException {
        server.expect(method(HttpMethod.POST))
                .andRespond(withSuccess(fixture("validation-report.json"), MediaType.APPLICATION_JSON));

        ValidationReportDto report = restClient.post().uri(URL).retrieve().body(ValidationReportDto.class);

        assertNotNull(report);
        assertEquals("https://example.org/slovnik", report.getOntologyIri());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), report.getTimestamp());
        assertEquals(2, report.getResults().size());

        ValidationResult local = report.getResults().get(0);
        assertEquals(ValidationSeverity.ERROR, local.severity());
        assertEquals("Pojem nemá název.", local.message());
        assertEquals("local-rule", local.ruleName());
        assertEquals("https://example.org/slovnik/pojem/a", local.focusNodeUri());
        assertEquals("http://www.w3.org/2004/02/skos/core#prefLabel", local.resultPathUri());
        assertNull(local.value());
        assertNull(local.nkdResource());

        ValidationResult global = report.getResults().get(1);
        assertEquals(ValidationSeverity.WARNING, global.severity());
        assertEquals("b", global.value());
        assertEquals(new NkdResource("https://slovník.gov.cz/pojem/b", "Pojem B"), global.nkdResource());
        server.verify();
    }

    @Test
    void errorResponse_messageIsExtracted() throws IOException {
        server.expect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(fixture("error-response.json")));

        HttpClientErrorException e = assertThrows(HttpClientErrorException.class,
                () -> restClient.post().uri(URL).retrieve().body(ValidationReportDto.class));

        assertEquals("Nepodporovaný typ souboru: slovnik.txt", new ValidatorErrorMessageExtractor().extract(e));
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = ValidatorContractTest.class.getResourceAsStream("/contract/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}