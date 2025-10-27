package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.controller.dto.ValidationRequestDto;
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

import java.util.stream.Stream;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ValidationClientTest {

    @Mock
    private RestClient restClient;

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
        validationClient = new ValidationClient(restClient);
        ReflectionTestUtils.setField(validationClient, "validationServiceUrl", validationServiceUrl);
    }

    // ========== Success Scenarios ==========

    @Test
    void testRequestValidation_Success() {
        // Arrange
        String ttlContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> .";
        String iri = "http://example.org/ontology";

        ValidationReportDto mockReport = mock(ValidationReportDto.class);

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(ValidationRequestDto.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(ValidationReportDto.class)).thenReturn(mockReport);

        // Act
        Optional<ValidationReport> result = validationClient.requestValidation(ttlContent, iri);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(mockReport, result.get());

        // Verify the URI was constructed correctly
        verify(requestBodyUriSpec).uri(validationServiceUrl + "/api/validator/validate");
        verify(requestBodySpec).header("Content-Type", "application/json");
    }

    @Test
    void testRequestValidation_VerifyRequestBody() {
        // Arrange
        String ttlContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> .";
        String iri = "http://example.org/ontology";

        ValidationReportDto mockReport = mock(ValidationReportDto.class);
        ArgumentCaptor<ValidationRequestDto> requestCaptor = ArgumentCaptor.forClass(ValidationRequestDto.class);

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(requestCaptor.capture())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(ValidationReportDto.class)).thenReturn(mockReport);

        // Act
        validationClient.requestValidation(ttlContent, iri);

        // Assert
        ValidationRequestDto capturedRequest = requestCaptor.getValue();
        assertNotNull(capturedRequest);
        assertEquals(ttlContent, capturedRequest.getOntologyContent());
        assertEquals(iri, capturedRequest.getIri());
    }

    // ========== Null Response Scenario ==========

    @Test
    void testRequestValidation_NullResponse() {
        // Arrange
        String ttlContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> .";
        String iri = "http://example.org/ontology";

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(ValidationRequestDto.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(ValidationReportDto.class)).thenReturn(null);

        // Act
        Optional<ValidationReport> result = validationClient.requestValidation(ttlContent, iri);

        // Assert
        assertFalse(result.isPresent());
    }

    // ========== Error Scenarios ==========

    @Test
    void testRequestValidation_RestClientException() {
        // Arrange
        String ttlContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> .";
        String iri = "http://example.org/ontology";

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(ValidationRequestDto.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenThrow(new RestClientException("Connection refused"));

        // Act
        Optional<ValidationReport> result = validationClient.requestValidation(ttlContent, iri);

        // Assert
        assertFalse(result.isPresent());
    }

    @Test
    void testRequestValidation_ServiceUnavailable() {
        // Arrange
        String ttlContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> .";
        String iri = "http://example.org/ontology";

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(ValidationRequestDto.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenThrow(new RestClientException("Service unavailable"));

        // Act
        Optional<ValidationReport> result = validationClient.requestValidation(ttlContent, iri);

        // Assert
        assertFalse(result.isPresent());
    }

    @Test
    void testRequestValidation_UnexpectedException() {
        // Arrange
        String ttlContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> .";
        String iri = "http://example.org/ontology";

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(ValidationRequestDto.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenThrow(new RuntimeException("Unexpected error"));

        // Act
        Optional<ValidationReport> result = validationClient.requestValidation(ttlContent, iri);

        // Assert
        assertFalse(result.isPresent());
    }

    @Test
    void testRequestValidation_NullPointerException() {
        // Arrange
        String ttlContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> .";
        String iri = "http://example.org/ontology";

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(ValidationRequestDto.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenThrow(new NullPointerException("Null pointer"));

        // Act
        Optional<ValidationReport> result = validationClient.requestValidation(ttlContent, iri);

        // Assert
        assertFalse(result.isPresent());
    }

    // ========== Input Validation Tests ==========

    private static Stream<Arguments> invalidInputProvider() {
        return Stream.of(
                Arguments.of("", "http://example.org/ontology", "empty TTL content"),
                Arguments.of(null, "http://example.org/ontology", "null TTL content"),
                Arguments.of("   \t\n  ", "http://example.org/ontology", "whitespace TTL content"),
                Arguments.of("@prefix owl: <http://www.w3.org/2002/07/owl#> .", "", "empty IRI"),
                Arguments.of("@prefix owl: <http://www.w3.org/2002/07/owl#> .", null, "null IRI")
        );
    }

    @ParameterizedTest
    @MethodSource("invalidInputProvider")
    void testRequestValidation_InvalidInputs(String ttlContent, String iri, String testDescription) {
        // Act
        Optional<ValidationReport> result = validationClient.requestValidation(ttlContent, iri);

        // Assert - should return empty Optional due to IllegalArgumentException caught by generic Exception handler
        assertFalse(result.isPresent(), "Should return empty Optional for: " + testDescription);
    }

    // ========== Edge Cases ==========

    @Test
    void testRequestValidation_VeryLargeTtlContent() {
        // Arrange
        String ttlContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n".repeat(10000);
        String iri = "http://example.org/ontology";

        ValidationReportDto mockReport = mock(ValidationReportDto.class);

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(ValidationRequestDto.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(ValidationReportDto.class)).thenReturn(mockReport);

        // Act
        Optional<ValidationReport> result = validationClient.requestValidation(ttlContent, iri);

        // Assert
        assertTrue(result.isPresent());
    }
}
