package com.dia.ismdtoolbackend.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "ai.service")
public class AiServiceConfig {

    private String url;

    @Min(1)
    @Max(10)
    private int suggestionCount = 5;

    @Min(1)
    @Max(100)
    private int maxJobIds = 100;

    private Duration connectTimeout = Duration.ofSeconds(5);

    private Duration readTimeout = Duration.ofSeconds(15);
}
