package com.dia.ismdtoolbackend.config;

import com.dia.ismdtoolbackend.exception.JenaTDB2Exception;
import org.apache.jena.query.Dataset;
import org.apache.jena.tdb2.TDB2Factory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

@Configuration
public class JenaConfig {

    @Value("${jena.tdb2.location:./data/tdb2}")
    private String tdb2Location;

    @Bean
    public Dataset jenaDataset() {
        try {
            Files.createDirectories(Paths.get(tdb2Location));
        } catch (IOException e) {
            throw new JenaTDB2Exception("Failed to create TDB2 directory: " + tdb2Location, e);
        }
        
        return TDB2Factory.connectDataset(tdb2Location);
    }
}