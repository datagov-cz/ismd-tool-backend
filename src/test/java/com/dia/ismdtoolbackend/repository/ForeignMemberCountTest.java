package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.outbox.InMemoryTdb2;
import org.apache.jena.query.Dataset;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.system.Txn;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE;
import static com.dia.constants.VocabularyConstants.TRIDA;
import static com.dia.constants.VocabularyConstants.VLASTNOST;
import static com.dia.constants.VocabularyConstants.VZTAH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link JenaTDB2Repository#countExternalDomainMembers} against a REAL in-memory
 * dataset, so the SPARQL semantics (per-kind attachment, graph exclusion, DISTINCT) are
 * verified rather than the query string.
 *
 * <p>The counts exist to reconcile the two detail surfaces: ontology detail lists only
 * own-graph members, concept detail merges cross-graph ones in
 * ({@link JenaTDB2Repository#fetchExternalDomainMembers}). Attachment must therefore match
 * the extractor per kind — vlastnost by {@code rdfs:domain} only, vztah by domain OR range.
 */
class ForeignMemberCountTest {

    private static final String GRAPH_A = "https://example.org/vocab-a";
    private static final String GRAPH_B = "https://example.org/vocab-b";
    private static final String CLASS_IRI = GRAPH_A + "/pojem/osoba";
    private static final String OTHER_CLASS_IRI = GRAPH_B + "/pojem/adresa";

    private InMemoryTdb2 repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTdb2();
    }

    @Test
    void countsForeignPropertyAndRelationship() {
        Model graphA = ModelFactory.createDefaultModel();
        klass(graphA, CLASS_IRI);

        Model graphB = ModelFactory.createDefaultModel();
        member(graphB, GRAPH_B + "/pojem/věk", VLASTNOST).addProperty(RDFS.domain, graphB.createResource(CLASS_IRI));
        member(graphB, GRAPH_B + "/pojem/bydlí-v", VZTAH).addProperty(RDFS.domain, graphB.createResource(CLASS_IRI));

        store(GRAPH_A, graphA);
        store(GRAPH_B, graphB);

        JenaTDB2Repository.ForeignMemberCount count = counts().get(CLASS_IRI);

        assertThat(count).isNotNull();
        assertThat(count.properties()).isEqualTo(1);
        assertThat(count.relationships()).isEqualTo(1);
    }

    /** Members living in the ontology's OWN graph are already listed, so they must not be counted. */
    @Test
    void ownGraphMembersAreNotCounted() {
        Model graphA = ModelFactory.createDefaultModel();
        klass(graphA, CLASS_IRI);
        member(graphA, GRAPH_A + "/pojem/jméno", VLASTNOST).addProperty(RDFS.domain, graphA.createResource(CLASS_IRI));
        member(graphA, GRAPH_A + "/pojem/zná", VZTAH).addProperty(RDFS.domain, graphA.createResource(CLASS_IRI));

        store(GRAPH_A, graphA);

        assertThat(counts()).doesNotContainKey(CLASS_IRI);
    }

    /**
     * A vztah whose RANGE is the class counts (concept detail lists it — the class need not be
     * the domain), while a vlastnost matched only by range does NOT: the extractor attaches
     * properties by {@code rdfs:domain} alone, so counting it would break reconciliation.
     */
    @Test
    void relationshipCountsByRange_propertyDoesNot() {
        Model graphA = ModelFactory.createDefaultModel();
        klass(graphA, CLASS_IRI);

        Model graphB = ModelFactory.createDefaultModel();
        member(graphB, GRAPH_B + "/pojem/má-bydliště", VZTAH)
                .addProperty(RDFS.domain, graphB.createResource(OTHER_CLASS_IRI))
                .addProperty(RDFS.range, graphB.createResource(CLASS_IRI));
        member(graphB, GRAPH_B + "/pojem/rozsah", VLASTNOST)
                .addProperty(RDFS.domain, graphB.createResource(OTHER_CLASS_IRI))
                .addProperty(RDFS.range, graphB.createResource(CLASS_IRI));

        store(GRAPH_A, graphA);
        store(GRAPH_B, graphB);

        JenaTDB2Repository.ForeignMemberCount count = counts().get(CLASS_IRI);

        assertThat(count).isNotNull();
        assertThat(count.relationships()).isEqualTo(1);
        assertThat(count.properties()).isNull();
    }

    /**
     * A vztah pointing both domain and range at the same class counts ONCE — mirroring the
     * extractor's dedupe (LinkedHashSet on the concept path, the range!=domain guard in
     * MemberIndex). Without COUNT(DISTINCT ?member) the UNION would double it.
     */
    @Test
    void selfReferencingRelationshipCountedOnce() {
        Model graphA = ModelFactory.createDefaultModel();
        klass(graphA, CLASS_IRI);

        Model graphB = ModelFactory.createDefaultModel();
        member(graphB, GRAPH_B + "/pojem/zná", VZTAH)
                .addProperty(RDFS.domain, graphB.createResource(CLASS_IRI))
                .addProperty(RDFS.range, graphB.createResource(CLASS_IRI));

        store(GRAPH_A, graphA);
        store(GRAPH_B, graphB);

        assertThat(counts().get(CLASS_IRI).relationships()).isEqualTo(1);
    }

    /** Members from several foreign graphs aggregate into one count per kind. */
    @Test
    void countsAggregateAcrossMultipleForeignGraphs() {
        Model graphA = ModelFactory.createDefaultModel();
        klass(graphA, CLASS_IRI);

        Model graphB = ModelFactory.createDefaultModel();
        member(graphB, GRAPH_B + "/pojem/věk", VLASTNOST).addProperty(RDFS.domain, graphB.createResource(CLASS_IRI));

        String graphC = "https://example.org/vocab-c";
        Model modelC = ModelFactory.createDefaultModel();
        member(modelC, graphC + "/pojem/barva", VLASTNOST).addProperty(RDFS.domain, modelC.createResource(CLASS_IRI));

        store(GRAPH_A, graphA);
        store(GRAPH_B, graphB);
        store(graphC, modelC);

        assertThat(counts().get(CLASS_IRI).properties()).isEqualTo(2);
    }

    /** A foreign concept that is neither vlastnost nor vztah (e.g. a class) is not a member. */
    @Test
    void nonMemberTypesAreIgnored() {
        Model graphA = ModelFactory.createDefaultModel();
        klass(graphA, CLASS_IRI);

        Model graphB = ModelFactory.createDefaultModel();
        member(graphB, GRAPH_B + "/pojem/podtřída", TRIDA).addProperty(RDFS.domain, graphB.createResource(CLASS_IRI));

        store(GRAPH_A, graphA);
        store(GRAPH_B, graphB);

        assertThat(counts()).doesNotContainKey(CLASS_IRI);
    }

    @Test
    void emptyOrUnsafeInputShortCircuits() {
        assertThat(repository.countExternalDomainMembers(GRAPH_A, List.of())).isEmpty();
        assertThat(repository.countExternalDomainMembers(GRAPH_A, null)).isEmpty();
        assertThat(repository.countExternalDomainMembers("not a valid iri", List.of(CLASS_IRI))).isEmpty();
        assertThat(repository.countExternalDomainMembers(GRAPH_A, List.of("not an iri"))).isEmpty();
    }

    // --- helpers ---

    private Map<String, JenaTDB2Repository.ForeignMemberCount> counts() {
        return repository.countExternalDomainMembers(GRAPH_A, List.of(CLASS_IRI, OTHER_CLASS_IRI));
    }

    private static Resource klass(Model model, String iri) {
        Resource r = model.createResource(iri);
        r.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + TRIDA));
        r.addProperty(SKOS.prefLabel, "Osoba", "cs");
        return r;
    }

    private static Resource member(Model model, String iri, String ofnType) {
        Resource r = model.createResource(iri);
        r.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + ofnType));
        return r;
    }

    private void store(String graphName, Model model) {
        Dataset dataset = repository.dataset();
        Txn.executeWrite(dataset, () -> dataset.addNamedModel(graphName, model));
    }
}
