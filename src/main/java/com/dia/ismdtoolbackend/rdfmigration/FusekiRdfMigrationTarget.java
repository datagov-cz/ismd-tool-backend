package com.dia.ismdtoolbackend.rdfmigration;

import com.dia.ismdtoolbackend.outbox.OutboxEntryRepository;
import com.dia.ismdtoolbackend.outbox.OutboxStatus;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;

import java.util.List;
import java.util.Objects;

/** The ISMD vocabulary graphs (from PostgreSQL) in Fuseki/TDB2, fed by the outbox. */
class FusekiRdfMigrationTarget implements RdfMigrationTarget {

    private final OntologyMetadataRepository ontologies;
    private final JenaTDB2Repository tdb2;
    private final OutboxEntryRepository outbox;

    FusekiRdfMigrationTarget(OntologyMetadataRepository ontologies, JenaTDB2Repository tdb2,
                             OutboxEntryRepository outbox) {
        this.ontologies = ontologies;
        this.tdb2 = tdb2;
        this.outbox = outbox;
    }

    @Override
    public List<String> graphNames() {
        return ontologies.findAllGraphNames().stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    @Override
    public List<String> storedGraphNames() {
        return tdb2.listNamedGraphs();
    }

    @Override
    public long unsettledWrites() {
        return outbox.countByStatus(OutboxStatus.PENDING) + outbox.countByStatus(OutboxStatus.FAILED);
    }

    @Override
    public void update(String sparqlUpdate) {
        tdb2.executeUpdate("RDF migration", sparqlUpdate);
    }
}
