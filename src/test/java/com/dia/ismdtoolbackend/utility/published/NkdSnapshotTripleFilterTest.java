package com.dia.ismdtoolbackend.utility.published;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NkdSnapshotTripleFilterTest {

    private static final String SKOS_CONCEPT = "http://www.w3.org/2004/02/skos/core#Concept";
    private static final String SKOS_PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    private static final String NKD_COPY_IRI = "https://slovník.gov.cz/agendový/104/pojem/adresní-místo";
    private static final String OWNER_IRI = "https://example.org/slovnik/mestys/pojem/budova";

    /** Owner graph holding one owned concept plus a materialized NKD snapshot copy. */
    private Model graphWithSnapshotAndOwned() {
        Model m = ModelFactory.createDefaultModel();

        Resource owned = m.getResource(OWNER_IRI);
        m.add(owned, m.createProperty(RDF_TYPE), m.getResource(SKOS_CONCEPT));
        m.add(owned, m.createProperty(SKOS_PREF_LABEL), "Budova");

        Resource copy = m.getResource(NKD_COPY_IRI);
        m.add(copy, m.createProperty(RDF_TYPE), m.getResource(SKOS_CONCEPT));
        m.add(copy, m.createProperty(SKOS_PREF_LABEL), "Adresní místo");
        m.add(copy, m.createProperty(NkdSnapshotMaterializer.NKD_SNAPSHOT_OF), owned);

        return m;
    }

    @Test
    void removesSnapshotSubjectAndAllItsTriples_keepsOwnedConcept() {
        Model m = graphWithSnapshotAndOwned();

        int removed = NkdSnapshotTripleFilter.removeSnapshotSubjects(m);

        assertThat(removed).isEqualTo(1);
        assertThat(m.getResource(NKD_COPY_IRI).listProperties().hasNext()).isFalse();
        assertThat(m.getResource(OWNER_IRI).hasProperty(m.createProperty(SKOS_PREF_LABEL))).isTrue();
    }

    @Test
    void noSnapshotSubjects_leavesModelUntouched() {
        Model m = ModelFactory.createDefaultModel();
        Resource owned = m.getResource(OWNER_IRI);
        m.add(owned, m.createProperty(RDF_TYPE), m.getResource(SKOS_CONCEPT));
        long before = m.size();

        int removed = NkdSnapshotTripleFilter.removeSnapshotSubjects(m);

        assertThat(removed).isZero();
        assertThat(m.size()).isEqualTo(before);
    }

    @Test
    void nullOrEmptyModel_returnsZero() {
        assertThat(NkdSnapshotTripleFilter.removeSnapshotSubjects(null)).isZero();
        assertThat(NkdSnapshotTripleFilter.removeSnapshotSubjects(ModelFactory.createDefaultModel())).isZero();
    }
}