package com.dia.ismdtoolbackend.config;

import lombok.Getter;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.rdfconnection.RDFConnectionRemote;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.util.concurrent.Semaphore;

/** Connection settings for the Fuseki store. */
@Configuration
@Getter
public class FusekiConnectionConfig {

    private final HttpClient fusekiHttpClient;
    private final Semaphore fusekiSemaphore;
    private final int fusekiSemaphoreTimeout;
    private final String fusekiEndpoint;

    public FusekiConnectionConfig(HttpClient fusekiHttpClient, Semaphore fusekiSemaphore,
                                  int fusekiSemaphoreTimeout,
                                  @Value("${jena.fuseki.url}") String fusekiEndpoint) {
        this.fusekiHttpClient = fusekiHttpClient;
        this.fusekiSemaphore = fusekiSemaphore;
        this.fusekiSemaphoreTimeout = fusekiSemaphoreTimeout;
        this.fusekiEndpoint = fusekiEndpoint;
    }

    public RDFConnection createConnection() {
        return RDFConnectionRemote.newBuilder()
                .destination(fusekiEndpoint)
                .queryEndpoint("sparql")
                .gspEndpoint("data")
                .updateEndpoint("update")
                .httpClient(fusekiHttpClient)
                .build();
    }
}
