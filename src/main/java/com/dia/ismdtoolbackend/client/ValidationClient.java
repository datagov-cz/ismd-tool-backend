package com.dia.ismdtoolbackend.client;

import com.dia.validation.ValidationReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;

import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class ValidationClient {

    @Value("${validation.service.url:http://localhost:8081}")
    private String validationServiceUrl;

    private final RestClient restClient;

    public Optional<ValidationReport> requestValidation(Long ontologyId) {
        return Optional.empty();
    }
}
