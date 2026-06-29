package com.dia.ismdtoolbackend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient restClient() {
        return RestClient.builder()
                .build();
    }

    /**
     * Dedicated client for the external validator, with explicit connect/read timeouts so a
     * slow or hung validator can never block a tool request thread. Kept separate from the
     * shared {@link #restClient()} bean so validator timeouts are isolated from any other
     * HTTP caller — mirroring the per-endpoint isolation of the SPARQL layer.
     */
    @Bean
    public RestClient validationRestClient(ValidationServiceConfig config) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(config.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(config.getReadTimeout());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}