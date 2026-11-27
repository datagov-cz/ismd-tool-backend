package com.dia.ismdtoolbackend.publication;

import com.dia.ismdtoolbackend.exception.PublicationFailedException;
import com.dia.ismdtoolbackend.exception.PublicationInProgressException;
import com.dia.ismdtoolbackend.publication.archive.PublicationArchive;
import com.dia.ismdtoolbackend.publication.archive.PublicationArchive.RunResult;
import com.dia.ismdtoolbackend.publication.archive.PublicationArchive.RunStart;
import com.dia.ismdtoolbackend.publication.archive.PublicationStatus;
import com.dia.ismdtoolbackend.publication.file.DiagramFileWriter;
import com.dia.ismdtoolbackend.publication.file.VocabularyFileWriter;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Concept;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Ontology;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshotReader;
import com.dia.ismdtoolbackend.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicationServiceTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final String PUBLISHED_IRI = "https://x/publikovany";
    private static final String DRAFT_IRI = "https://x/rozpracovany";
    private static final String BY = "ADMIN:test";
    private static final long ID = 7L;

    private final MutableClock clock = MutableClock.of("2026-10-07T01:00:00Z");
    private final PublicationSnapshotReader reader = mock(PublicationSnapshotReader.class);
    private final PublicationConfig config = new PublicationConfig();
    private final PublicationArchive archive = mock(PublicationArchive.class);
    private final ExecutorService thread = Executors.newSingleThreadExecutor();
    private final AsyncTaskExecutor executor = new TaskExecutorAdapter(thread);

    /** What the archive received: the run, each file and its content, read while the file still exists. */
    private RunResult stored;
    private final Map<String, BuiltFile> storedFiles = new HashMap<>();
    private final Map<String, byte[]> storedContent = new HashMap<>();

    private PublicationService service;

    @BeforeEach
    void setUp() {
        config.setContextUrl("https://example.org/kontext.jsonld");
        when(reader.read()).thenReturn(snapshot());
        when(archive.begin(any())).thenReturn(ID);
        org.mockito.Mockito.doAnswer(invocation -> {
            stored = invocation.getArgument(1);
            Map<String, BuiltFile> files = invocation.getArgument(2);
            storedFiles.putAll(files);
            for (BuiltFile f : files.values()) {
                storedContent.put(f.name(), Files.readAllBytes(f.path()));
            }
            return null;
        }).when(archive).store(eq(ID), any(), any());
        service = service(new DiagramFileWriter(), executor);
    }

    @AfterEach
    void tearDown() {
        thread.shutdownNow();
    }

    private PublicationService service(DiagramFileWriter diagrams, AsyncTaskExecutor executor) {
        VocabularyFileWriter vocabularies = new VocabularyFileWriter((id, format) -> {
            throw new com.dia.ismdtoolbackend.exception.EmptyDataException("prázdný");
        }, config);
        return new PublicationService(config, reader, vocabularies, diagrams, archive, clock, executor);
    }

    private static PublicationSnapshot snapshot() {
        return PublicationSnapshot.of(List.of(
                new Ontology(1L, PUBLISHED_IRI, "publikovany", true, null, null, T, T,
                        List.of(new Concept(PUBLISHED_IRI + "/pojem/a", "a", true, T, T))),
                new Ontology(2L, DRAFT_IRI, "rozpracovany", false, null, null, T, T, List.of())),
                List.of(), Set.of());
    }

    @Test
    void publishAndWait_storesBothFiles_withContentHashEtags_andTheRun() throws Exception {
        assertThat(service.publish(BY, true)).isEqualTo(
                new PublicationRun(ID, PublicationStatus.STORED, clock.instant(), 2, 0, true, true));

        verify(archive).begin(new RunStart(clock.instant(), BY, true, "https://example.org/kontext.jsonld"));
        assertThat(storedFiles).containsOnlyKeys(VocabularyFileWriter.FILE, DiagramFileWriter.FILE);
        for (BuiltFile file : storedFiles.values()) {
            byte[] content = storedContent.get(file.name());
            assertThat(file.size()).isEqualTo(content.length);
            assertThat(file.etag()).isEqualTo("\"" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)) + "\"");
        }
        assertThat(new String(storedContent.get(VocabularyFileWriter.FILE), StandardCharsets.UTF_8))
                .contains(PUBLISHED_IRI, DRAFT_IRI);
        assertThat(stored).isEqualTo(new RunResult(true, 2, 0));
        verify(archive, never()).fail(anyLong(), anyString());
    }

    @Test
    void publish_returnsTheRunningRun_andBuildsInTheBackground() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(reader.read()).thenAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return snapshot();
        });

        PublicationRun run = service.publish(BY, false);

        assertThat(run).isEqualTo(new PublicationRun(ID, PublicationStatus.RUNNING, clock.instant(), 0, 0, false, true));
        verify(archive, never()).store(anyLong(), any(), any());
        release.countDown();
        verify(archive, timeout(5000)).store(eq(ID), eq(new RunResult(true, 2, 0)), any());
    }

    @Test
    void publish_removesTheTemporaryBuild() {
        service.publish(BY, true);

        Path build = storedFiles.get(DiagramFileWriter.FILE).path().getParent();
        assertThat(build).doesNotExist();
    }

    @Test
    void includeDraftsOff_leavesDraftVocabulariesOut() {
        config.setIncludeDrafts(false);

        PublicationRun run = service.publish(BY, true);

        assertThat(new String(storedContent.get(VocabularyFileWriter.FILE), StandardCharsets.UTF_8))
                .contains(PUBLISHED_IRI).doesNotContain(DRAFT_IRI)
                .contains("\"obsahuje_rozpracované\":false");
        assertThat(run.includeDrafts()).isFalse();
        assertThat(stored.vocabularies()).isEqualTo(1);
        verify(archive).begin(new RunStart(clock.instant(), BY, false, "https://example.org/kontext.jsonld"));
    }

    @Test
    void snapshotFailure_failsTheRun_recordsWhy_andStoresNothing() {
        when(reader.read()).thenThrow(new IllegalStateException("PostgreSQL down"));

        assertThatThrownBy(() -> service.publish(BY, true)).isInstanceOf(PublicationFailedException.class)
                .hasRootCauseMessage("PostgreSQL down");

        verify(archive).fail(ID, "IllegalStateException: PostgreSQL down");
        verify(archive, never()).store(anyLong(), any(), any());
    }

    @Test
    void failureWithoutWaiting_isRecordedOnTheRow() {
        when(reader.read()).thenThrow(new IllegalStateException("PostgreSQL down"));

        assertThat(service.publish(BY, false).status()).isEqualTo(PublicationStatus.RUNNING);

        verify(archive, timeout(5000)).fail(ID, "IllegalStateException: PostgreSQL down");
        verify(archive, never()).store(anyLong(), any(), any());
    }

    @Test
    void writerFailure_failsTheRun_andStoresNothing() {
        DiagramFileWriter failing = mock(DiagramFileWriter.class);
        doThrow(new IllegalStateException("disk full")).when(failing).write(any(), any(), any());

        assertThatThrownBy(() -> service(failing, executor).publish(BY, true))
                .isInstanceOf(PublicationFailedException.class);

        verify(archive).fail(ID, "IllegalStateException: disk full");
        verify(archive, never()).store(anyLong(), any(), any());
    }

    @Test
    void archiveFailure_failsTheRun() {
        doThrow(new IllegalStateException("PostgreSQL down")).when(archive).store(anyLong(), any(), any());

        assertThatThrownBy(() -> service.publish(BY, true)).isInstanceOf(PublicationFailedException.class)
                .hasRootCauseMessage("PostgreSQL down");
        verify(archive).fail(ID, "IllegalStateException: PostgreSQL down");
    }

    @Test
    void missingContextUrl_recordsNothing() {
        config.setContextUrl(" ");

        assertThatThrownBy(() -> service.publish(BY, false)).isInstanceOf(PublicationFailedException.class)
                .hasMessageContaining("context-url");

        verify(archive, never()).begin(any());
        verify(reader, never()).read();
        config.setContextUrl("https://example.org/kontext.jsonld");
        assertThat(service.publish(BY, false).status()).as("the lock is released")
                .isEqualTo(PublicationStatus.RUNNING);
    }

    @Test
    void secondRunWhileOneIsInProgress_isRejected_andTheLockIsFreedWhenTheRunEnds() throws Exception {
        CountDownLatch inRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(reader.read()).thenAnswer(invocation -> {
            inRead.countDown();
            release.await(5, TimeUnit.SECONDS);
            throw new IllegalStateException("PostgreSQL down");
        });
        service.publish(BY, false);
        assertThat(inRead.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.publish(BY, false)).isInstanceOf(PublicationInProgressException.class);

        release.countDown();
        verify(archive, timeout(5000)).fail(eq(ID), anyString());
        org.mockito.Mockito.doReturn(snapshot()).when(reader).read();
        assertThat(waitForLock().status()).isEqualTo(PublicationStatus.STORED);
    }

    /** The failed build releases the lock just after recording the failure. */
    private PublicationRun waitForLock() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            try {
                return service.publish(BY, true);
            } catch (PublicationInProgressException e) {
                Thread.sleep(20);
            }
        }
        throw new AssertionError("the lock was never released");
    }

    @Test
    void rejectedByTheExecutor_failsTheRow_andReleasesTheLock() {
        AsyncTaskExecutor rejecting = new TaskExecutorAdapter(task -> {
            throw new RejectedExecutionException("shutting down");
        });
        PublicationService rejected = service(new DiagramFileWriter(), rejecting);

        assertThatThrownBy(() -> rejected.publish(BY, false)).isInstanceOf(PublicationFailedException.class);
        verify(archive).fail(eq(ID), anyString());
        assertThatThrownBy(() -> rejected.publish(BY, false)).as("not PUBLICATION_IN_PROGRESS")
                .isInstanceOf(PublicationFailedException.class);
    }

    @Test
    void failureToRecordTheRun_failsAndReleasesTheLock() {
        when(archive.begin(any())).thenThrow(new IllegalStateException("PostgreSQL down"));

        assertThatThrownBy(() -> service.publish(BY, false)).isInstanceOf(PublicationFailedException.class);
        assertThatThrownBy(() -> service.publish(BY, false)).isInstanceOf(PublicationFailedException.class);
        verify(reader, never()).read();
    }

    @Test
    void startup_failsRunsLeftRunning_andSurvivesADatabaseFailure() {
        service.afterSingletonsInstantiated();
        verify(archive).failInterrupted("přerušeno restartem");

        when(archive.failInterrupted(anyString())).thenThrow(new IllegalStateException("PostgreSQL down"));
        service.afterSingletonsInstantiated();
    }

    @Test
    void summary_isTheRootCauseInOneLine() {
        assertThat(PublicationService.summary(new IllegalStateException("outer",
                new java.io.IOException("  disk full \n")))).isEqualTo("IOException: disk full");
        assertThat(PublicationService.summary(new NullPointerException())).isEqualTo("NullPointerException");
    }
}
