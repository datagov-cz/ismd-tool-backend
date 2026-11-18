package com.dia.ismdtoolbackend.publication;

import com.dia.ismdtoolbackend.exception.PublicationFailedException;
import com.dia.ismdtoolbackend.exception.PublicationInProgressException;
import com.dia.ismdtoolbackend.publication.archive.PublicationArchive;
import com.dia.ismdtoolbackend.publication.archive.PublicationArchive.RunRecord;
import com.dia.ismdtoolbackend.publication.file.DiagramFileWriter;
import com.dia.ismdtoolbackend.publication.file.VocabularyFileWriter;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Concept;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Ontology;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshotReader;
import com.dia.ismdtoolbackend.testsupport.time.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicationServiceTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final String PUBLISHED_IRI = "https://x/publikovany";
    private static final String DRAFT_IRI = "https://x/rozpracovany";
    private static final String BY = "ADMIN:test";

    private final MutableClock clock = MutableClock.of("2026-10-07T01:00:00Z");
    private final PublicationSnapshotReader reader = mock(PublicationSnapshotReader.class);
    private final PublicationConfig config = new PublicationConfig();
    private final PublicationArchive archive = mock(PublicationArchive.class);

    /** What the archive received: the run record, each file and its content, read while the file still exists. */
    private RunRecord stored;
    private final Map<String, BuiltFile> storedFiles = new HashMap<>();
    private final Map<String, byte[]> storedContent = new HashMap<>();

    private PublicationService service;

    @BeforeEach
    void setUp() {
        config.setContextUrl("https://example.org/kontext.jsonld");
        when(reader.read()).thenReturn(snapshot());
        when(archive.save(any(), any())).thenAnswer(invocation -> {
            stored = invocation.getArgument(0);
            Map<String, BuiltFile> files = invocation.getArgument(1);
            storedFiles.putAll(files);
            for (BuiltFile f : files.values()) {
                storedContent.put(f.name(), Files.readAllBytes(f.path()));
            }
            return 7L;
        });
        service = service(new DiagramFileWriter());
    }

    private PublicationService service(DiagramFileWriter diagrams) {
        VocabularyFileWriter vocabularies = new VocabularyFileWriter((id, format) -> {
            throw new com.dia.ismdtoolbackend.exception.EmptyDataException("prázdný");
        });
        return new PublicationService(config, reader, vocabularies, diagrams, archive, clock);
    }

    private static PublicationSnapshot snapshot() {
        return new PublicationSnapshot(List.of(
                new Ontology(1L, PUBLISHED_IRI, "publikovany", true, null, null, T, T,
                        List.of(new Concept(PUBLISHED_IRI + "/pojem/a", "a", true, T, T))),
                new Ontology(2L, DRAFT_IRI, "rozpracovany", false, null, null, T, T, List.of())),
                List.of(), Set.of());
    }

    @Test
    void publish_storesBothFiles_withContentHashEtags_andTheRun() throws Exception {
        assertThat(service.publish(BY)).isEqualTo(new PublicationRun(7L, clock.instant(), 2, 0, true, true));

        assertThat(storedFiles).containsOnlyKeys(VocabularyFileWriter.FILE, DiagramFileWriter.FILE);
        for (BuiltFile file : storedFiles.values()) {
            byte[] content = storedContent.get(file.name());
            assertThat(file.size()).isEqualTo(content.length);
            assertThat(file.etag()).isEqualTo("\"" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)) + "\"");
        }
        assertThat(new String(storedContent.get(VocabularyFileWriter.FILE), StandardCharsets.UTF_8))
                .contains(PUBLISHED_IRI, DRAFT_IRI);
        assertThat(stored).isEqualTo(new RunRecord(clock.instant(), BY, true, true, 2, 0,
                "https://example.org/kontext.jsonld"));
    }

    @Test
    void publish_removesTheTemporaryBuild() {
        service.publish(BY);

        Path build = storedFiles.get(DiagramFileWriter.FILE).path().getParent();
        assertThat(build).doesNotExist();
    }

    @Test
    void includeDraftsOff_leavesDraftVocabulariesOut() {
        config.setIncludeDrafts(false);

        service.publish(BY);

        assertThat(new String(storedContent.get(VocabularyFileWriter.FILE), StandardCharsets.UTF_8))
                .contains(PUBLISHED_IRI).doesNotContain(DRAFT_IRI)
                .contains("\"obsahuje_rozpracované\":false");
        assertThat(stored.includeDrafts()).isFalse();
    }

    @Test
    void snapshotFailure_failsTheRun_andStoresNothing() {
        when(reader.read()).thenThrow(new IllegalStateException("PostgreSQL down"));

        assertThatThrownBy(() -> service.publish(BY)).isInstanceOf(PublicationFailedException.class)
                .hasRootCauseMessage("PostgreSQL down");

        verify(archive, never()).save(any(), any());
    }

    @Test
    void writerFailure_failsTheRun_andStoresNothing() {
        DiagramFileWriter failing = mock(DiagramFileWriter.class);
        doThrow(new IllegalStateException("disk full")).when(failing).write(any(), any(), any());

        assertThatThrownBy(() -> service(failing).publish(BY)).isInstanceOf(PublicationFailedException.class);

        verify(archive, never()).save(any(), any());
    }

    @Test
    void archiveFailure_failsTheRun() {
        doThrow(new IllegalStateException("PostgreSQL down")).when(archive).save(any(), any());

        assertThatThrownBy(() -> service.publish(BY)).isInstanceOf(PublicationFailedException.class)
                .hasRootCauseMessage("PostgreSQL down");
    }

    @Test
    void missingContextUrl_publishesNothing() {
        config.setContextUrl(" ");

        assertThatThrownBy(() -> service.publish(BY)).isInstanceOf(PublicationFailedException.class)
                .hasMessageContaining("context-url");

        verify(reader, never()).read();
        verify(archive, never()).save(any(), any());
    }

    @Test
    void secondRunWhileOneIsInProgress_isRejected() throws Exception {
        CountDownLatch inRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(reader.read()).thenAnswer(invocation -> {
            inRead.countDown();
            release.await(5, TimeUnit.SECONDS);
            return snapshot();
        });
        CompletableFuture<PublicationRun> first = CompletableFuture.supplyAsync(() -> service.publish(BY));
        assertThat(inRead.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.publish(BY)).isInstanceOf(PublicationInProgressException.class);

        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS).id()).isEqualTo(7L);
    }
}
