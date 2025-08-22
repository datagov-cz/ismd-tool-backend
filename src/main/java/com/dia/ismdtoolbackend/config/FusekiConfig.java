package com.dia.ismdtoolbackend.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.Dataset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

@Configuration
@Slf4j
public class FusekiConfig {

    @Value("${jena.fuseki.port:3030}")
    private int fusekiPort;

    @Value("${jena.dataset-name:ismd-tool-dataset}")
    private String datasetName;

    private FusekiServer fusekiServer;

    private final Dataset jenaDataset;

    public FusekiConfig(Dataset jenaDataset) {
        this.jenaDataset = jenaDataset;
    }

    @Bean
    @DependsOn("jenaDataset")
    public FusekiServer fusekiServer() {
        this.fusekiServer = FusekiServer.create()
                .port(fusekiPort)
                .add("/" + datasetName, jenaDataset)
                .build();
        return this.fusekiServer;
    }

    @PostConstruct
    public void startFuseki() {
        if (fusekiServer != null) {
            fusekiServer.start();
            log.info("Fuseki server started at http://localhost:{}", fusekiPort);
            log.info("Dataset available at: http://localhost:{}/{}", fusekiPort, datasetName);
        }
    }

    @PreDestroy
    public void stopFuseki() {
        if (fusekiServer != null) {
            fusekiServer.stop();
        }
    }
}
