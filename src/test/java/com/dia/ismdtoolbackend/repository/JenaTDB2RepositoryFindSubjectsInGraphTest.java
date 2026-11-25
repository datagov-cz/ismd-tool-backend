package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.outbox.InMemoryTdb2;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link JenaTDB2Repository#findSubjectsInGraph} against a real in-memory dataset. */
class JenaTDB2RepositoryFindSubjectsInGraphTest {

    private static final String GRAPH = "https://slovník.gov.cz/g";
    private static final String OTHER_GRAPH = "https://slovník.gov.cz/other";

    private final InMemoryTdb2 repository = new InMemoryTdb2();

    @BeforeEach
    void setUp() {
        Model g = ModelFactory.createDefaultModel();
        g.add(g.createResource(GRAPH + "/pojem/subject"), RDFS.label, "subjekt");
        g.add(g.createResource(GRAPH + "/pojem/holder"), RDFS.seeAlso, g.createResource(GRAPH + "/pojem/object-only"));
        repository.saveOntologyModel(GRAPH, g);

        Model other = ModelFactory.createDefaultModel();
        other.add(other.createResource(GRAPH + "/pojem/elsewhere"), RDFS.label, "jinde");
        repository.saveOntologyModel(OTHER_GRAPH, other);
    }

    @Test
    void returnsOnlyIrisThatAreSubjectsInThatGraph() {
        assertThat(repository.findSubjectsInGraph(GRAPH, List.of(
                GRAPH + "/pojem/subject",
                GRAPH + "/pojem/object-only",
                GRAPH + "/pojem/elsewhere",
                GRAPH + "/pojem/absent")))
                .containsExactly(GRAPH + "/pojem/subject");
    }

    @Test
    void queriesInBatchesWithoutLosingMatches() {
        List<String> iris = new ArrayList<>(IntStream.range(0, 1200)
                .mapToObj(i -> GRAPH + "/pojem/absent-" + i).toList());
        iris.add(GRAPH + "/pojem/subject");

        assertThat(repository.findSubjectsInGraph(GRAPH, iris)).containsExactly(GRAPH + "/pojem/subject");
    }

    @Test
    void unsafeIrisAreSkipped() {
        assertThat(repository.findSubjectsInGraph("not an iri", List.of(GRAPH + "/pojem/subject"))).isEmpty();
        assertThat(repository.findSubjectsInGraph(GRAPH, List.of("bad iri", GRAPH + "/pojem/subject")))
                .containsExactly(GRAPH + "/pojem/subject");
        assertThat(repository.findSubjectsInGraph(GRAPH, List.of())).isEmpty();
    }
}
