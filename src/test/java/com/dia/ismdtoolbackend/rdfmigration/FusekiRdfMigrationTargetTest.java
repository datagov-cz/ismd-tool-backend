package com.dia.ismdtoolbackend.rdfmigration;

import com.dia.ismdtoolbackend.outbox.InMemoryTdb2;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FusekiRdfMigrationTargetTest {

    @Mock private OntologyMetadataRepository ontologies;

    private final InMemoryTdb2 tdb2 = new InMemoryTdb2();

    @Test
    void graphNamesAreTheVocabulariesSortedWithoutBlanksOrDuplicates() {
        when(ontologies.findAllGraphNames()).thenReturn(Arrays.asList(
                "https://example.org/b", null, "https://example.org/a", "https://example.org/b"));

        assertThat(new FusekiRdfMigrationTarget(ontologies, tdb2).graphNames())
                .containsExactly("https://example.org/a", "https://example.org/b");
    }

    @Test
    void updateRunsAgainstTheStore() {
        new FusekiRdfMigrationTarget(ontologies, tdb2).update(
                "INSERT DATA { GRAPH <https://example.org/v> { <https://example.org/s> <https://example.org/p> 1 } }");

        Model graph = tdb2.dataset().getNamedModel("https://example.org/v");
        assertThat(graph.size()).isEqualTo(1);
    }

    @Test
    void configurationWiresTheBundledChangesetsToTheStore() {
        RdfMigrationRunner runner = new RdfMigrationConfiguration().rdfMigrationRunner(null, ontologies, tdb2);

        assertThat(runner).isNotNull();
    }
}
