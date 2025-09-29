package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.exception.JenaTDB2Exception;
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
            throw new JenaTDB2Exception("Cannot initialize Jena TDB2", e);
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

    public void saveConcept(Resource conceptResource) {
        if (conceptResource == null) {
            throw new IllegalArgumentException("Concept resource cannot be null");
        }

        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();

            model.add(conceptResource.getModel());

            dataset.commit();

            String conceptURI = conceptResource.getURI();
            log.info("Successfully saved concept to TDB2: {}", conceptURI);

        } catch (Exception e) {
            dataset.abort();
            log.error("Failed to save concept to TDB2: {}", conceptResource.getURI(), e);
            throw new JenaTDB2Exception("Nepodařilo se uložit pojem do TDB2", e);
        } finally {
            dataset.end();
        }
    }

    public boolean conceptExists(String conceptUri) {
        if (conceptUri == null || conceptUri.trim().isEmpty()) {
            return false;
        }

        dataset.begin(ReadWrite.READ);
        try {
            Model model = dataset.getDefaultModel();
            Resource resource = model.getResource(conceptUri);

            boolean exists = model.containsResource(resource) &&
                    resource.listProperties().hasNext();

            log.debug("Concept existence check for '{}': {}", conceptUri, exists);
            return exists;

        } catch (Exception e) {
            log.error("Error checking concept existence for URI: {}", conceptUri, e);
            return false;
        } finally {
            dataset.end();
        }
    }
    
    public boolean deleteConcept(String conceptUri) {
        if (conceptUri == null || conceptUri.trim().isEmpty()) {
            throw new IllegalArgumentException("Concept URI cannot be null or empty");
        }

        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            Resource resource = model.getResource(conceptUri);

            if (!model.containsResource(resource)) {
                log.warn("Cannot delete concept - not found: {}", conceptUri);
                dataset.abort();
                return false;
            }

            model.removeAll(resource, null, null);
            model.removeAll(null, null, resource);

            dataset.commit();
            log.info("Successfully deleted concept from TDB2: {}", conceptUri);
            return true;

        } catch (Exception e) {
            dataset.abort();
            log.error("Failed to delete concept from TDB2: {}", conceptUri, e);
            throw new JenaTDB2Exception("Nepodařilo se odstranit pojem z TDB2", e);
        } finally {
            dataset.end();
        }
    }
}
