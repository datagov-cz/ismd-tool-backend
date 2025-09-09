package com.dia.ismdtoolbackend.config;

import org.apache.jena.query.Dataset;
import org.apache.jena.tdb2.TDB2Factory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.nio.file.Files;
import java.nio.file.Path;

@TestConfiguration
public class TestJenaConfig {

    @Bean
    @Primary
    public Dataset jenaDataset() throws Exception {
        // Create a temporary directory for test TDB2 database
        Path tempDir = Files.createTempDirectory("test-tdb2-");
        tempDir.toFile().deleteOnExit();
        
        // Create an in-memory TDB2 dataset for testing
        return TDB2Factory.createDataset();
    }
}