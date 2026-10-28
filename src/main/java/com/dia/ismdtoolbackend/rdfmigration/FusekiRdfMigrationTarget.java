package com.dia.ismdtoolbackend.rdfmigration;

import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;

import java.util.List;
import java.util.Objects;

/** The ISMD vocabulary graphs (from PostgreSQL) in Fuseki/TDB2. */
class FusekiRdfMigrationTarget implements RdfMigrationTarget {

    private final OntologyMetadataRepository ontologies;
    private final JenaTDB2Repository tdb2;

    FusekiRdfMigrationTarget(OntologyMetadataRepository ontologies, JenaTDB2Repository tdb2) {
        this.ontologies = ontologies;
        this.tdb2 = tdb2;
    }

    @Override
    public List<String> graphNames() {
        return ontologies.findAllGraphNames().stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    @Override
    public void update(String sparqlUpdate) {
        tdb2.executeUpdate("RDF migration", sparqlUpdate);
    }
}
