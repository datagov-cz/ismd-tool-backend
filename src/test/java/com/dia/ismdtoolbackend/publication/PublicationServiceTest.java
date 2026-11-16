package com.dia.ismdtoolbackend.publication;

import com.dia.ismdtoolbackend.publication.file.DiagramFileWriter;
import com.dia.ismdtoolbackend.publication.file.VocabularyFileWriter;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Concept;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Ontology;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshotReader;
import com.dia.ismdtoolbackend.testsupport.time.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicationServiceTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final String PUBLISHED_IRI = "https://x/publikovany";
    private static final String DRAFT_IRI = "https://x/rozpracovany";

    @TempDir Path dir;

    private final MutableClock clock = MutableClock.of("2026-10-07T01:00:00Z");
    private final PublicationSnapshotReader reader = mock(PublicationSnapshotReader.class);
    private final PublicationConfig config = new PublicationConfig();
    private PublicationStore store;
    private PublicationService service;

    @BeforeEach
    void setUp() {
        config.setDirectory(dir);
        config.setContextUrl("https://example.org/kontext.jsonld");
        when(reader.read()).thenReturn(snapshot());
        store = new PublicationStore(config);
        service = service(store);
    }

    private PublicationService service(PublicationStore s) {
        VocabularyFileWriter vocabularies = new VocabularyFileWriter((id, format) -> {
            throw new com.dia.ismdtoolbackend.exception.EmptyDataException("prázdný");
        });
        return new PublicationService(config, reader, vocabularies, new DiagramFileWriter(), s, clock);
    }

    private static PublicationSnapshot snapshot() {
        return new PublicationSnapshot(List.of(
                new Ontology(1L, PUBLISHED_IRI, "publikovany", true, null, null, T, T,
                        List.of(new Concept(PUBLISHED_IRI + "/pojem/a", "a", true, T, T))),
                new Ontology(2L, DRAFT_IRI, "rozpracovany", false, null, null, T, T, List.of())),
                List.of(), Set.of());
    }

    @Test
    void publish_commitsBothFiles_withContentHashEtags() throws Exception {
        assertThat(service.publish()).isTrue();

        for (String name : List.of(VocabularyFileWriter.FILE, DiagramFileWriter.FILE)) {
            PublishedFile file = store.file(name).orElseThrow();
            byte[] content = Files.readAllBytes(file.path());
            assertThat(file.size()).isEqualTo(content.length);
            assertThat(file.etag()).isEqualTo("\"" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)) + "\"");
            assertThat(file.publishedAt()).isEqualTo(clock.instant());
        }
        assertThat(read(VocabularyFileWriter.FILE)).contains(PUBLISHED_IRI, DRAFT_IRI);
    }

    @Test
    void restart_reloadsTheCurrentPublicationAndItsAge() {
        service.publish();

        PublicationStore restarted = new PublicationStore(config);
        restarted.load();

        assertThat(restarted.publishedAt()).contains(clock.instant());
        assertThat(restarted.file(DiagramFileWriter.FILE).orElseThrow().etag())
                .isEqualTo(store.file(DiagramFileWriter.FILE).orElseThrow().etag());
        assertThat(service(restarted).isDue()).isFalse();
    }

    @Test
    void restart_discardsAPublicationWhoseFileIsTruncated() throws IOException {
        service.publish();
        Files.write(store.file(VocabularyFileWriter.FILE).orElseThrow().path(), new byte[] {'{'});

        PublicationStore restarted = new PublicationStore(config);
        restarted.load();

        assertThat(restarted.publishedAt()).isEmpty();
        assertThat(service(restarted).isDue()).isTrue();
    }

    @Test
    void isDue_untilPublished_thenAgainOnceTheIntervalHasPassed() {
        config.setInterval(Duration.ofDays(30));
        assertThat(service.isDue()).as("nothing published").isTrue();

        service.publish();
        assertThat(service.isDue()).isFalse();

        clock.advance(Duration.ofDays(30).minusSeconds(1));
        assertThat(service.publishIfDue()).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(service.isDue()).as("exactly the interval").isTrue();
        assertThat(service.publishIfDue()).isTrue();
        assertThat(store.publishedAt()).contains(clock.instant());
    }

    @Test
    void newPublication_replacesTheOldBuildDirectory() throws IOException {
        service.publish();
        Path first = store.file(DiagramFileWriter.FILE).orElseThrow().path().getParent();
        clock.advance(Duration.ofDays(31));

        service.publish();

        assertThat(first).doesNotExist();
        try (Stream<Path> builds = Files.list(dir).filter(Files::isDirectory)) {
            assertThat(builds).hasSize(1);
        }
    }

    @Test
    void failedRun_keepsThePreviousPublication_andRemovesItsBuild() throws IOException {
        service.publish();
        PublishedFile before = store.file(VocabularyFileWriter.FILE).orElseThrow();
        clock.advance(Duration.ofDays(31));
        when(reader.read()).thenThrow(new IllegalStateException("PostgreSQL down"));

        assertThat(service.publish()).isFalse();

        assertThat(store.file(VocabularyFileWriter.FILE)).contains(before);
        assertThat(before.path()).exists();
        try (Stream<Path> builds = Files.list(dir).filter(Files::isDirectory)) {
            assertThat(builds).hasSize(1);
        }
    }

    @Test
    void failureWhileWriting_removesThePartialBuild() throws IOException {
        DiagramFileWriter failing = mock(DiagramFileWriter.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("disk full")).when(failing)
                .write(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        PublicationService withFailingWriter = new PublicationService(config, reader,
                new VocabularyFileWriter((id, format) -> "{}"), failing, store, clock);

        assertThat(withFailingWriter.publish()).isFalse();

        assertThat(store.publishedAt()).isEmpty();
        try (Stream<Path> left = Files.list(dir)) {
            assertThat(left).as("no partial build, no marker").isEmpty();
        }
    }

    @Test
    void missingContextUrl_publishesNothing() {
        config.setContextUrl(" ");

        assertThat(service.publish()).isFalse();

        assertThat(store.publishedAt()).isEmpty();
        verify(reader, never()).read();
    }

    @Test
    void includeDraftsOff_leavesDraftVocabulariesOut() throws IOException {
        config.setIncludeDrafts(false);

        service.publish();

        assertThat(read(VocabularyFileWriter.FILE)).contains(PUBLISHED_IRI).doesNotContain(DRAFT_IRI)
                .contains("\"obsahuje_rozpracované\":false");
    }

    @Test
    void secondRunWhileOneIsInProgress_isSkipped() throws Exception {
        CountDownLatch inRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(reader.read()).thenAnswer(invocation -> {
            inRead.countDown();
            release.await(5, TimeUnit.SECONDS);
            return snapshot();
        });
        CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(service::publish);
        assertThat(inRead.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(service.publish()).isFalse();

        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
    }

    private String read(String name) throws IOException {
        return Files.readString(store.file(name).orElseThrow().path(), StandardCharsets.UTF_8);
    }
}
