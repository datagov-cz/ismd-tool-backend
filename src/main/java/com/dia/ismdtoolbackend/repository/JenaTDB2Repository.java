package com.dia.ismdtoolbackend.repository;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.tdb2.TDB2Factory;
import org.springframework.beans.factory.annotation.Value;
import org.apache.jena.query.Dataset;
import org.springframework.stereotype.Repository;

@Repository
@Slf4j
public class JenaTDB2Repository {

    @Value("${jena.tdb2.location}")
    private String tdb2Location;

    private Dataset dataset;

    @PostConstruct
    public void init() {
        try {
            this.dataset = TDB2Factory.connectDataset(tdb2Location);
            log.info("Connected to Jena TDB2 at location: {}", tdb2Location);
        } catch (Exception e) {
            log.error("Failed to connect to Jena TDB2 at location: {}", tdb2Location, e);
            throw new RuntimeException("Cannot initialize Jena TDB2", e);
        }
    }

    @PreDestroy
    public void cleanup() {
        if (dataset != null) {
            try {
                dataset.close();
                log.info("Closed Jena TDB2 dataset");
            } catch (Exception e) {
                log.error("Error closing Jena TDB2 dataset", e);
            }
        }
    }
}
