package com.dia.ismdtoolbackend.rdfmigration;

import com.dia.ismdtoolbackend.ofn.ConformanceIssue;
import com.dia.ismdtoolbackend.ofn.OfnConformanceGate;
import com.dia.ismdtoolbackend.ofn.OfnIris;
import com.dia.ismdtoolbackend.ofn.OfnRdfReader;
import com.dia.ismdtoolbackend.ofn.OfnSpecification;
import com.dia.ismdtoolbackend.ofn.OfnWriteGate;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.update.UpdateAction;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * All bundled changesets, in order, on two vocabularies dumped from a local store written by the old
 * code: before, the OFN gate blocks them; after, only missing data (T3) is left, and a second run changes
 * nothing.
 */
class BundledChangesetsOnRealDataTest {

    private static final OfnSpecification SPEC = OfnSpecification.bundled();
    private static final OfnWriteGate GATE = new OfnWriteGate(new OfnRdfReader(SPEC, OfnIris.MARKERS),
            new OfnConformanceGate(SPEC, OfnIris.MARKERS));

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "doprava.nt, https://slovník.gov.cz/doprava, 0",
            "parity-test-brana.nt, https://slovník.gov.cz/parity-test-brana, 1"
    })
    void oldShapesAreMigratedAndOnlyMissingDataRemains(String file, String graph, int incomplete) {
        Model stored = RDFDataMgr.loadModel("rdfmigration/" + file);
        assertThat(GATE.inspectVocabulary(stored, graph).issues())
                .as("the fixture should start in the old shapes").isNotEmpty();
        Dataset dataset = DatasetFactory.createTxnMem();
        dataset.getNamedModel(graph).add(stored);
        List<RdfChangeset> changesets = new RdfChangesetLoader(RdfChangesetLoader.DEFAULT_LOCATION).load();

        changesets.forEach(changeset -> UpdateAction.parseExecute(changeset.boundTo(graph), dataset));
        Model once = ModelFactory.createDefaultModel().add(dataset.getNamedModel(graph));
        changesets.forEach(changeset -> UpdateAction.parseExecute(changeset.boundTo(graph), dataset));

        assertThat(dataset.getNamedModel(graph).isIsomorphicWith(once)).as("second run changed the graph").isTrue();
        List<ConformanceIssue> issues = GATE.inspectVocabulary(once, graph).issues();
        assertThat(issues).as(issues.toString()).hasSize(incomplete)
                .allMatch(issue -> issue.kind() == ConformanceIssue.Kind.CONCEPT_INCOMPLETE);
    }
}
