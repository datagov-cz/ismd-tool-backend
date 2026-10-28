package com.dia.ismdtoolbackend.rdfmigration;

import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires the startup RDF migration. On by default; {@code rdf-migration.enabled=false} switches it off
 * (test contexts with no Fuseki).
 */
@Configuration
@ConditionalOnProperty(name = "rdf-migration.enabled", havingValue = "true", matchIfMissing = true)
public class RdfMigrationConfiguration {

    @Bean
    public RdfMigrationRunner rdfMigrationRunner(RdfMigrationRepository history,
                                                 OntologyMetadataRepository ontologies,
                                                 JenaTDB2Repository tdb2) {
        return new RdfMigrationRunner(new RdfChangesetLoader(RdfChangesetLoader.DEFAULT_LOCATION), history,
                new FusekiRdfMigrationTarget(ontologies, tdb2), Clock.systemDefaultZone());
    }
}
