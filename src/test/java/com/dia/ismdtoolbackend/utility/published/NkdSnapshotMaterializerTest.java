package com.dia.ismdtoolbackend.utility.published;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NkdSnapshotMaterializerTest {

    private static final String SKOS_IN_SCHEME = "http://www.w3.org/2004/02/skos/core#inScheme";
    private static final String SKOS_PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";
    private static final String RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";

    private static final String NKD_IRI = "https://slovník.gov.cz/agendový/104/pojem/adresní-místo";
    private static final String NKD_SCHEME = "https://slovník.gov.cz/agendový/104";
    private static final String OWNER_IRI = "https://example.org/slovnik/mestys/pojem/budova";

    private NkdSnapshotMaterializer materializer;

    @BeforeEach
    void setUp() {
        materializer = new NkdSnapshotMaterializer();
    }

    /** Raw NKD model: a foreign concept with its own (foreign) inScheme + a label. */
    private Model rawNkdModel() {
        Model m = ModelFactory.createDefaultModel();
        Resource concept = m.getResource(NKD_IRI);
        m.add(concept, m.createProperty(SKOS_IN_SCHEME), m.getResource(NKD_SCHEME));
        m.add(concept, m.createProperty(SKOS_PREF_LABEL), "Adresní místo");
        return m;
    }

    @Test
    void materialize_carriesRawTriplesAndProvenanceMarker() {
        Set<Statement> result = materializer.materialize(rawNkdModel(), NKD_IRI, OWNER_IRI);

        // Non-inScheme raw triples preserved (e.g. the prefLabel).
        assertThat(result).anyMatch(s -> s.getSubject().getURI().equals(NKD_IRI)
                && s.getPredicate().getURI().equals(SKOS_PREF_LABEL));

        // provenance marker present and pointing owner-ward.
        assertThat(result).anyMatch(s -> s.getPredicate().getURI().equals(NkdSnapshotMaterializer.NKD_SNAPSHOT_OF)
                && s.getSubject().getURI().equals(NKD_IRI)
                && s.getObject().asResource().getURI().equals(OWNER_IRI));
    }

    @Test
    void materialize_stripsInScheme() {
        // NKD's own foreign inScheme is dropped from the stored payload — it adds nothing to the
        // deviation comparison, and keeps the persisted N-Triples free of foreign scheme metadata.
        Set<Statement> result = materializer.materialize(rawNkdModel(), NKD_IRI, OWNER_IRI);

        assertThat(result).noneMatch(s -> s.getPredicate().getURI().equals(SKOS_IN_SCHEME));
    }

    @Test
    void materialize_noInScheme_producesCopyAndProvenance() {
        Model m = ModelFactory.createDefaultModel();
        Resource concept = m.getResource(NKD_IRI);
        m.add(concept, m.createProperty(RDFS_LABEL), "No scheme concept");

        Set<Statement> result = materializer.materialize(m, NKD_IRI, OWNER_IRI);

        assertThat(result).anyMatch(s -> s.getPredicate().getURI().equals(NkdSnapshotMaterializer.NKD_SNAPSHOT_OF));
    }

    @Test
    void nTriples_roundTrip_preservesStatements() {
        Set<Statement> original = materializer.materialize(rawNkdModel(), NKD_IRI, OWNER_IRI);

        String serialized = materializer.toNTriples(original);
        Set<Statement> parsed = materializer.parse(serialized);

        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void toNTriples_empty_isEmptyString() {
        assertThat(materializer.toNTriples(Set.of())).isEmpty();
        assertThat(materializer.parse("")).isEmpty();
        assertThat(materializer.parse(null)).isEmpty();
    }

    @Test
    void materialize_emptyRawModel_returnsEmpty() {
        assertThat(materializer.materialize(ModelFactory.createDefaultModel(), NKD_IRI, OWNER_IRI))
                .isEmpty();
        assertThat(materializer.materialize(null, NKD_IRI, OWNER_IRI)).isEmpty();
    }
}
