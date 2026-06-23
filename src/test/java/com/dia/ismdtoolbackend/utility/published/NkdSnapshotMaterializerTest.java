package com.dia.ismdtoolbackend.utility.published;

import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NkdSnapshotMaterializerTest {

    private static final String SKOS_IN_SCHEME = "http://www.w3.org/2004/02/skos/core#inScheme";
    private static final String SKOS_PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";
    private static final String RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";

    private static final String NKD_IRI = "https://slovník.gov.cz/agendový/104/pojem/adresní-místo";
    private static final String NKD_SCHEME = "https://slovník.gov.cz/agendový/104";
    private static final String OWNER_IRI = "https://example.org/slovnik/mestys/pojem/budova";
    private static final String OWNER_SCHEME = "https://example.org/slovnik/mestys";

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
        Set<Statement> result = materializer.materialize(rawNkdModel(), NKD_IRI, OWNER_IRI, OWNER_SCHEME);

        // Raw triples preserved.
        assertThat(result).anyMatch(s -> s.getSubject().getURI().equals(NKD_IRI)
                && s.getPredicate().getURI().equals(SKOS_IN_SCHEME)
                && s.getObject().asResource().getURI().equals(NKD_SCHEME));

        // M2: provenance marker present and pointing owner-ward.
        assertThat(result).anyMatch(s -> s.getPredicate().getURI().equals(NkdSnapshotMaterializer.NKD_SNAPSHOT_OF)
                && s.getSubject().getURI().equals(NKD_IRI)
                && s.getObject().asResource().getURI().equals(OWNER_IRI));
    }

    @Test
    void materialize_foreignInScheme_doesNotPrefixMatchOwnerScheme() {
        Set<Statement> result = materializer.materialize(rawNkdModel(), NKD_IRI, OWNER_IRI, OWNER_SCHEME);

        // M3 in practice: no materialized subject's IRI starts with the owner scheme.
        assertThat(result).allSatisfy(s -> {
            if (s.getSubject().isURIResource()) {
                assertThat(s.getSubject().getURI()).doesNotStartWith(OWNER_SCHEME);
            }
        });
    }

    @Test
    void materialize_noInScheme_isSafe() {
        Model m = ModelFactory.createDefaultModel();
        Resource concept = m.getResource(NKD_IRI);
        m.add(concept, m.createProperty(RDFS_LABEL), "No scheme concept");

        Set<Statement> result = materializer.materialize(m, NKD_IRI, OWNER_IRI, OWNER_SCHEME);

        // Still produces the copy + provenance, no exception.
        assertThat(result).anyMatch(s -> s.getPredicate().getURI().equals(NkdSnapshotMaterializer.NKD_SNAPSHOT_OF));
    }

    @Test
    void materialize_subjectUnderOwnerScheme_throws() {
        // Pathological: a "materialized" subject that IS under the owner scheme and declares an
        // inScheme → the reconciler would enumerate it as owned in the owner graph. Must abort (M3).
        Model m = ModelFactory.createDefaultModel();
        String ownedSubject = OWNER_SCHEME + "/pojem/sneaky";
        Resource concept = m.getResource(ownedSubject);
        m.add(concept, m.createProperty(SKOS_IN_SCHEME), m.getResource(OWNER_SCHEME));

        assertThatThrownBy(() -> materializer.materialize(m, ownedSubject, OWNER_IRI, OWNER_SCHEME))
                .isInstanceOf(OntologyException.class)
                .hasMessageContaining("treat it as owned");
    }

    @Test
    void materialize_foreignNkdSchemePrefixingOwnerScheme_doesNotThrow() {
        // Regression for the over-broad guard: a local owner scheme that is a PATH-CHILD of an NKD
        // scheme (realistic with slovník.gov.cz IRIs). The NKD subject prefix-matches its OWN foreign
        // scheme but NOT the owner scheme, so the reconciler would never claim it in the owner graph.
        // The guard must compare subject-vs-owner-scheme, not scheme-vs-scheme — so this must pass.
        String nkdScheme = "https://slovník.gov.cz/agendový/104";
        String nkdIri = nkdScheme + "/pojem/adresní-místo";
        String localOwnerScheme = nkdScheme + "/local";   // owner scheme is a child path of the NKD scheme
        String localOwnerIri = localOwnerScheme + "/pojem/budova";

        Model m = ModelFactory.createDefaultModel();
        Resource concept = m.getResource(nkdIri);
        m.add(concept, m.createProperty(SKOS_IN_SCHEME), m.getResource(nkdScheme));
        m.add(concept, m.createProperty(SKOS_PREF_LABEL), "Adresní místo");

        Set<Statement> result = materializer.materialize(m, nkdIri, localOwnerIri, localOwnerScheme);

        assertThat(result).anyMatch(s ->
                s.getPredicate().getURI().equals(NkdSnapshotMaterializer.NKD_SNAPSHOT_OF));
    }

    @Test
    void nTriples_roundTrip_preservesStatements() {
        Set<Statement> original = materializer.materialize(rawNkdModel(), NKD_IRI, OWNER_IRI, OWNER_SCHEME);

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
        assertThat(materializer.materialize(ModelFactory.createDefaultModel(), NKD_IRI, OWNER_IRI, OWNER_SCHEME))
                .isEmpty();
        assertThat(materializer.materialize(null, NKD_IRI, OWNER_IRI, OWNER_SCHEME)).isEmpty();
    }
}
