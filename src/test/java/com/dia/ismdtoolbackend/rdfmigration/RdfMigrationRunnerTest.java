package com.dia.ismdtoolbackend.rdfmigration;

import com.dia.ismdtoolbackend.testsupport.context.FullContextTest;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.update.UpdateAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The runner against the real tracking table (Liquibase on H2) and an in-memory RDF dataset.
 */
@FullContextTest
@ExtendWith(OutputCaptureExtension.class)
@ActiveProfiles("local")
@TestPropertySource(properties = "full-context.db-name=rdf-migration")
class RdfMigrationRunnerTest {

    private static final String V1 = "https://example.org/v1";
    private static final String V2 = "https://example.org/v2";
    private static final String MARK = """
            # Marks every vocabulary.
            INSERT { GRAPH ?graph { ?graph <https://example.org/migrated> true } }
            WHERE { GRAPH ?graph { ?graph a <https://example.org/Vocabulary> } }
            """;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC);

    @Autowired RdfMigrationRepository history;
    @Autowired ApplicationContext context;

    @TempDir Path dir;

    private final Dataset dataset = DatasetFactory.createTxnMem();
    private final List<String> updates = new ArrayList<>();
    private boolean failOnV2;
    private List<String> stored = List.of(V1, V2);
    private long unsettled;
    private long updateDelayMs;

    private final RdfMigrationTarget target = new RdfMigrationTarget() {
        @Override
        public List<String> graphNames() {
            return List.of(V1, V2);
        }

        @Override
        public List<String> storedGraphNames() {
            return stored;
        }

        @Override
        public long unsettledWrites() {
            return unsettled;
        }

        @Override
        public void update(String sparqlUpdate) {
            if (updateDelayMs > 0) {
                try {
                    Thread.sleep(updateDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (failOnV2 && sparqlUpdate.contains("<" + V2 + ">")) {
                throw new IllegalStateException("Fuseki down");
            }
            updates.add(sparqlUpdate);
            UpdateAction.parseExecute(sparqlUpdate, dataset);
        }
    };

    @BeforeEach
    void seed() {
        history.deleteAll();
        for (String graph : List.of(V1, V2)) {
            Model model = dataset.getNamedModel(graph);
            model.add(model.createResource(graph), org.apache.jena.vocabulary.RDF.type,
                    model.createResource("https://example.org/Vocabulary"));
        }
    }

    private RdfMigrationRunner runner() {
        return new RdfMigrationRunner(new RdfChangesetLoader("file:" + dir.toAbsolutePath()), history, target, CLOCK,
                java.time.Duration.ofMillis(300));
    }

    private boolean migrated(String graph) {
        Model model = dataset.getNamedModel(graph);
        return model.contains(model.createResource(graph),
                ResourceFactory.createProperty("https://example.org/migrated"), model.createTypedLiteral(true));
    }

    @Test
    void runnerIsOffInTestContexts() {
        assertThat(context.getBeansOfType(RdfMigrationRunner.class)).isEmpty();
    }

    @Test
    void pendingChangesetIsAppliedToEveryGraphAndRecorded() throws IOException {
        Files.writeString(dir.resolve("001-mark.ru"), MARK);

        assertThat(runner().migrate()).containsExactly("001-mark");

        assertThat(migrated(V1)).isTrue();
        assertThat(migrated(V2)).isTrue();
        RdfMigrationEntity row = history.findById("001-mark").orElseThrow();
        assertThat(row.getChecksum()).isEqualTo(RdfChangesetLoader.sha256(MARK));
        assertThat(row.getDescription()).isEqualTo("Marks every vocabulary.");
        assertThat(row.getGraphsProcessed()).isEqualTo(2);
        assertThat(row.getAppliedAt()).isEqualTo(LocalDateTime.of(2026, 10, 28, 10, 0));
    }

    @Test
    void appliedChangesetIsNotRunAgain() throws IOException {
        Files.writeString(dir.resolve("001-mark.ru"), MARK);
        runner().migrate();
        updates.clear();

        assertThat(runner().migrate()).isEmpty();
        assertThat(updates).isEmpty();
    }

    @Test
    void changesetsRunInIdOrderAndOnlyThePendingOnes() throws IOException {
        Files.writeString(dir.resolve("001-mark.ru"), MARK);
        runner().migrate();
        Files.writeString(dir.resolve("003-third.ru"), MARK.replace("migrated", "third"));
        Files.writeString(dir.resolve("002-second.ru"), MARK.replace("migrated", "second"));

        assertThat(runner().migrate()).containsExactly("002-second", "003-third");
        assertThat(history.findAll()).hasSize(3);
    }

    @Test
    void changedAppliedChangesetFailsWithoutRunningAnything() throws IOException {
        Files.writeString(dir.resolve("001-mark.ru"), MARK);
        runner().migrate();
        Files.writeString(dir.resolve("001-mark.ru"), MARK + "\n# edited\n");
        Files.writeString(dir.resolve("002-second.ru"), MARK.replace("migrated", "second"));
        updates.clear();

        assertThatThrownBy(() -> runner().migrate())
                .hasMessageContaining("001-mark was changed after it was applied");
        assertThat(updates).isEmpty();
        assertThat(history.existsById("002-second")).isFalse();
    }

    @Test
    void failureOnAGraphFailsAndRecordsNothingSoTheNextStartupRetries() throws IOException {
        Files.writeString(dir.resolve("001-mark.ru"), MARK);
        failOnV2 = true;

        assertThatThrownBy(() -> runner().migrate())
                .hasMessageContaining("001-mark failed on graph " + V2)
                .hasMessageContaining("Fuseki down");
        assertThat(history.existsById("001-mark")).isFalse();

        failOnV2 = false;
        assertThat(runner().migrate()).containsExactly("001-mark");
        assertThat(migrated(V1)).isTrue();
        assertThat(migrated(V2)).isTrue();
    }

    @Test
    void anAppliedChangesetWhoseFileIsGone_isReportedAndNothingRuns(CapturedOutput output) throws IOException {
        Files.writeString(dir.resolve("001-mark.ru"), MARK);
        runner().migrate();
        Files.delete(dir.resolve("001-mark.ru"));
        updates.clear();

        assertThat(runner().migrate()).isEmpty();
        assertThat(updates).isEmpty();
        assertThat(output).contains("RDF migration 001-mark is recorded as applied but its changeset file is missing");
    }

    @Test
    void noChangesetsTouchesNoGraph() {
        assertThat(runner().migrate()).isEmpty();
        assertThat(updates).isEmpty();
    }

    @Test
    void unsettledOutboxFailsBeforeAnyGraphIsTouched() throws IOException {
        Files.writeString(dir.resolve("001-mark.ru"), MARK);
        unsettled = 2;

        assertThatThrownBy(() -> runner().migrate())
                .hasMessageContaining("2 outbox row(s) are PENDING or FAILED")
                .hasMessageContaining("001-mark");
        assertThat(updates).isEmpty();
        assertThat(history.count()).isZero();
    }

    @Test
    void unsettledOutboxDoesNotBlockStartupWhenNothingIsPending() {
        unsettled = 5;

        assertThat(runner().migrate()).isEmpty();
    }

    @Test
    void vocabularyGraphMissingFromTdb2FailsBeforeAnyGraphIsTouched() throws IOException {
        Files.writeString(dir.resolve("001-mark.ru"), MARK);
        stored = List.of(V1, "https://example.org/v%32");

        assertThatThrownBy(() -> runner().migrate())
                .hasMessageContaining("in PostgreSQL but not in TDB2: [" + V2 + "]");
        assertThat(updates).isEmpty();
    }

    @Test
    void tdb2GraphWithoutAVocabularyIsLeftAloneAndTheRestMigrates() throws IOException {
        Files.writeString(dir.resolve("001-mark.ru"), MARK);
        stored = List.of(V1, V2, "https://example.org/orphan");

        assertThat(runner().migrate()).containsExactly("001-mark");
        assertThat(updates).hasSize(2).noneMatch(update -> update.contains("orphan"));
    }

    @Test
    void updateThatOutlivesTheTimeoutFailsAndRecordsNothing() throws IOException {
        Files.writeString(dir.resolve("001-mark.ru"), MARK);
        updateDelayMs = 5_000;

        assertThatThrownBy(() -> runner().migrate())
                .hasMessageContaining("001-mark timed out after PT0.3S on graph " + V1);
        assertThat(history.count()).isZero();
    }
}
