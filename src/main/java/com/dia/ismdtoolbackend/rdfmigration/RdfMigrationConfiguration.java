package com.dia.ismdtoolbackend.rdfmigration;

import com.dia.ismdtoolbackend.outbox.OutboxEntryRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

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
                                                 JenaTDB2Repository tdb2,
                                                 OutboxEntryRepository outbox,
                                                 @Value("${rdf-migration.location:" + RdfChangesetLoader.DEFAULT_LOCATION + "}")
                                                 String location,
                                                 @Value("${rdf-migration.update-timeout:PT30M}") Duration updateTimeout) {
        return new RdfMigrationRunner(new RdfChangesetLoader(location), history,
                new FusekiRdfMigrationTarget(ontologies, tdb2, outbox), Clock.systemUTC(), updateTimeout);
    }
}
