package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.client.validation.ValidationCallExecutor;
import com.dia.ismdtoolbackend.config.ValidationServiceConfig;
import com.dia.ismdtoolbackend.controller.dto.ValidationRequestDto;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.exception.ValidationServiceUnavailableException;
import com.dia.validation.ValidationReport;
import com.dia.validation.ValidationReportDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The reworked client routes every call through {@link ValidationCallExecutor}. A REAL executor
 * (with a generous breaker so a single failure never opens it) is used so these tests exercise
 * the actual exception mapping. The {@code validationRestClient} is mocked to drive the wire.
 *
 * <p>Key contract change vs the old client: the STRICT path now PROPAGATES typed exceptions
 * (503 unavailable / 400 rejection) instead of collapsing everything to {@code Optional.empty()};
 * only the LENIENT path swallows.
 */
@ExtendWith(MockitoExtension.class)
class ValidationClientTest {

    @Mock
    private RestClient validationRestClient;
    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock
    private RestClient.RequestBodySpec requestBodySpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;

    private ValidationClient validationClient;

    private final String validationServiceUrl = "http://localhost:8080";

    @BeforeEach
    void setUp() {
        ValidationServiceConfig config = new ValidationServiceConfig();
        config.setUrl(validationServiceUrl);
        // High threshold so individual failure tests never trip the breaker.
        config.getBreaker().setFailureThreshold(1000);
        ValidationCallExecutor executor = new ValidationCallExecutor(config);
        validationClient = new ValidationClient(config, validationRestClient, executor);
    }

    private void stubChain() {
        when(validationRestClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(ValidationRequestDto.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
    }

    // ========== Success ==========

    @Test
    void requestValidation_success_returnsReport() {
        ValidationReportDto mockReport = mock(ValidationReportDto.class);
        stubChain();
        when(responseSpec.body(ValidationReportDto.class)).thenReturn(mockReport);

        Optional<ValidationReport> result = validationClient.requestValidation("@prefix x: <x> .", "http://example.org/ontology");

        assertTrue(result.isPresent());
        assertEquals(mockReport, result.get());
        verify(requestBodyUriSpec).uri(validationServiceUrl + "/api/validator/validate");
        verify(requestBodySpec).header("Content-Type", "application/json");
    }

    @Test
    void requestValidation_sendsCorrectBody() {
        ValidationReportDto mockReport = mock(ValidationReportDto.class);
        ArgumentCaptor<ValidationRequestDto> captor = ArgumentCaptor.forClass(ValidationRequestDto.class);
        when(validationRestClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(captor.capture())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(ValidationReportDto.class)).thenReturn(mockReport);

        validationClient.requestValidation("@prefix x: <x> .", "http://example.org/ontology");

        ValidationRequestDto sent = captor.getValue();
        assertEquals("@prefix x: <x> .", sent.getOntologyContent());
        assertEquals("http://example.org/ontology", sent.getIri());
    }

    @Test
    void requestValidation_nullBody_returnsEmpty() {
        stubChain();
        when(responseSpec.body(ValidationReportDto.class)).thenReturn(null);

        Optional<ValidationReport> result = validationClient.requestValidation("@prefix x: <x> .", "http://example.org/ontology");

        assertFalse(result.isPresent());
    }

    // ========== Strict path: distinct failure types ==========

    @Test
    void requestValidation_validatorDown_throwsUnavailable() {
        stubChain();
        when(responseSpec.body(ValidationReportDto.class))
                .thenThrow(new ResourceAccessException("Connection refused"));

        assertThrows(ValidationServiceUnavailableException.class,
                () -> validationClient.requestValidation("@prefix x: <x> .", "http://example.org/ontology"));
    }

    @Test
    void requestValidation_validator5xx_throwsUnavailable() {
        stubChain();
        when(responseSpec.body(ValidationReportDto.class))
                .thenThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "err", null, null, null));

        assertThrows(ValidationServiceUnavailableException.class,
                () -> validationClient.requestValidation("@prefix x: <x> .", "http://example.org/ontology"));
    }

    @Test
    void requestValidation_validator4xx_throwsRejectionWithValidatorMessage() {
        stubChain();
        String body = "{\"error\":\"Bad Request\",\"message\":\"Invalid TTL syntax: line 3\"}";
        when(responseSpec.body(ValidationReportDto.class))
                .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                        null, body.getBytes(), null));

        OntologyValidationException ex = assertThrows(OntologyValidationException.class,
                () -> validationClient.requestValidation("bad ttl", "http://example.org/ontology"));
        assertTrue(ex.getMessage().contains("Invalid TTL syntax"),
                "should surface the validator's own message, was: " + ex.getMessage());
    }

    // ========== Lenient (upload) path: swallows everything ==========

    @Test
    void requestValidationLenient_validatorDown_returnsEmpty() {
        stubChain();
        when(responseSpec.body(ValidationReportDto.class))
                .thenThrow(new ResourceAccessException("Connection refused"));

        Optional<ValidationReport> result =
                validationClient.requestValidationLenient("@prefix x: <x> .", "http://example.org/ontology");

        assertFalse(result.isPresent());
    }

    @Test
    void requestValidationLenient_validatorRejected_returnsEmpty() {
        stubChain();
        when(responseSpec.body(ValidationReportDto.class))
                .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                        null, "{\"message\":\"bad\"}".getBytes(), null));

        Optional<ValidationReport> result =
                validationClient.requestValidationLenient("bad", "http://example.org/ontology");

        assertFalse(result.isPresent());
    }

    @Test
    void requestValidationLenient_success_returnsReport() {
        ValidationReportDto mockReport = mock(ValidationReportDto.class);
        stubChain();
        when(responseSpec.body(ValidationReportDto.class)).thenReturn(mockReport);

        Optional<ValidationReport> result =
                validationClient.requestValidationLenient("@prefix x: <x> .", "http://example.org/ontology");

        assertTrue(result.isPresent());
    }

    // ========== Input validation (pre-network, throws) ==========

    private static Stream<Arguments> invalidInputProvider() {
        return Stream.of(
                Arguments.of("", "http://example.org/ontology"),
                Arguments.of(null, "http://example.org/ontology"),
                Arguments.of("   \t\n  ", "http://example.org/ontology"),
                Arguments.of("@prefix x: <x> .", ""),
                Arguments.of("@prefix x: <x> .", (String) null)
        );
    }

    @ParameterizedTest
    @MethodSource("invalidInputProvider")
    void requestValidation_invalidInputs_throwIllegalArgument(String ttlContent, String iri) {
        assertThrows(IllegalArgumentException.class,
                () -> validationClient.requestValidation(ttlContent, iri));
    }
}
