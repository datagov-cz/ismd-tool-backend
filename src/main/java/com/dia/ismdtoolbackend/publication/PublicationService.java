package com.dia.ismdtoolbackend.publication;

import com.dia.ismdtoolbackend.exception.PublicationFailedException;
import com.dia.ismdtoolbackend.exception.PublicationInProgressException;
import com.dia.ismdtoolbackend.publication.archive.PublicationArchive;
import com.dia.ismdtoolbackend.publication.archive.PublicationArchive.RunResult;
import com.dia.ismdtoolbackend.publication.archive.PublicationArchive.RunStart;
import com.dia.ismdtoolbackend.publication.archive.PublicationStatus;
import com.dia.ismdtoolbackend.publication.file.DiagramFileWriter;
import com.dia.ismdtoolbackend.publication.file.PublicationHeader;
import com.dia.ismdtoolbackend.publication.file.VocabularyFileWriter;
import com.dia.ismdtoolbackend.publication.snapshot.DraftFilter;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshotReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Builds one publication on demand in the background: record the run as {@code RUNNING}, read
 * PostgreSQL as the files are written, apply the draft rule, write both files into a temporary
 * directory and store them in the archive, from which an admin downloads them. Runs at most once at a
 * time; a failed run is recorded {@code FAILED} and stores no files. On startup, runs left
 * {@code RUNNING} by a stopped instance are failed.
 */
@Slf4j
@Service
public class PublicationService implements SmartInitializingSingleton {

    static final String INTERRUPTED_BY_RESTART = "přerušeno restartem";
    private static final String FAILED = "Publikace selhala; nic nebylo uloženo.";

    private final PublicationConfig config;
    private final PublicationSnapshotReader snapshotReader;
    private final VocabularyFileWriter vocabularyWriter;
    private final DiagramFileWriter diagramWriter;
    private final PublicationArchive archive;
    private final Clock clock;
    private final AsyncTaskExecutor executor;
    /** Released by whichever thread ends the run, hence a semaphore rather than a lock. */
    private final Semaphore running = new Semaphore(1);

    public PublicationService(PublicationConfig config, PublicationSnapshotReader snapshotReader,
                              VocabularyFileWriter vocabularyWriter, DiagramFileWriter diagramWriter,
                              PublicationArchive archive, Clock clock,
                              @Qualifier(PublicationExecutorConfig.PUBLICATION_EXECUTOR) AsyncTaskExecutor executor) {
        this.config = config;
        this.snapshotReader = snapshotReader;
        this.vocabularyWriter = vocabularyWriter;
        this.diagramWriter = diagramWriter;
        this.archive = archive;
        this.clock = clock;
        this.executor = executor;
    }

    /**
     * Starts a publication run.
     *
     * @param triggeredBy who started the run, recorded with it
     * @param wait        whether to return only once the run is done
     * @return the {@code RUNNING} run, or with {@code wait} the {@code STORED} run
     * @throws PublicationInProgressException when another run is in progress
     * @throws PublicationFailedException     when the run cannot start, or with {@code wait} when it fails
     */
    public PublicationRun publish(String triggeredBy, boolean wait) {
        if (!running.tryAcquire()) {
            log.info("Publication by {}: a run is already in progress, skipping", triggeredBy);
            throw new PublicationInProgressException("Publikace již probíhá.");
        }
        boolean handedOff = false;
        try {
            String contextUrl = config.getContextUrl();
            if (contextUrl == null || contextUrl.isBlank()) {
                log.error("Publication: ismd.publication.context-url is not set, nothing published");
                throw new PublicationFailedException("ismd.publication.context-url není nastavena.", null);
            }
            boolean includeDrafts = config.isIncludeDrafts();
            Instant publishedAt = clock.instant();
            long id = begin(new RunStart(publishedAt, triggeredBy, includeDrafts, contextUrl));
            Future<PublicationRun> run = submit(id, () -> build(id, publishedAt, includeDrafts, contextUrl));
            handedOff = true;
            log.info("Publication {} started by {}", id, triggeredBy);
            return wait ? await(run) : PublicationRun.running(id, publishedAt, includeDrafts);
        } finally {
            if (!handedOff) {
                running.release();
            }
        }
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            int interrupted = archive.failInterrupted(INTERRUPTED_BY_RESTART);
            if (interrupted > 0) {
                log.warn("Publication: {} run(s) left RUNNING by a previous instance marked FAILED", interrupted);
            }
        } catch (RuntimeException e) {
            log.error("Publication: could not fail runs left RUNNING by a previous instance", e);
        }
    }

    private long begin(RunStart start) {
        try {
            return archive.begin(start);
        } catch (RuntimeException e) {
            log.error("Publication of {}: could not record the run", start.publishedAt(), e);
            throw new PublicationFailedException(FAILED, e);
        }
    }

    private Future<PublicationRun> submit(long id, Callable<PublicationRun> build) {
        try {
            return executor.submit(() -> {
                try {
                    return build.call();
                } finally {
                    running.release();
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("Publication {}: the build could not be started", id, e);
            failQuietly(id, summary(e));
            throw new PublicationFailedException(FAILED, e);
        }
    }

    private static PublicationRun await(Future<PublicationRun> run) {
        try {
            return run.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof PublicationFailedException failed) {
                throw failed;
            }
            throw new PublicationFailedException(FAILED, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublicationFailedException("Čekání na publikaci bylo přerušeno; publikace pokračuje.", e);
        }
    }

    private PublicationRun build(long id, Instant publishedAt, boolean includeDrafts, String contextUrl) {
        Path dir = null;
        try {
            PublicationSnapshot snapshot = snapshotReader.read();
            if (!includeDrafts) {
                snapshot = DraftFilter.withoutDrafts(snapshot);
            }
            PublicationHeader header = new PublicationHeader(publishedAt, includeDrafts, contextUrl,
                    config.getIriBase());
            PublicationSnapshot published = snapshot;
            dir = Files.createTempDirectory("ismd-publication-");
            boolean[] complete = {true};
            int[] diagrams = {0};
            Map<String, BuiltFile> files = new LinkedHashMap<>();
            files.put(VocabularyFileWriter.FILE, writeFile(dir, VocabularyFileWriter.FILE,
                    out -> complete[0] = vocabularyWriter.write(published, header, out)));
            files.put(DiagramFileWriter.FILE, writeFile(dir, DiagramFileWriter.FILE,
                    out -> diagrams[0] = diagramWriter.write(published, header, out)));
            int vocabularies = snapshot.ontologies().size();
            archive.store(id, new RunResult(complete[0], vocabularies, diagrams[0]), files);
            log.info("Publication {} of {} stored: {} vocabularies ({}), {} diagrams", id, publishedAt,
                    vocabularies, complete[0] ? "complete" : "INCOMPLETE", diagrams[0]);
            return new PublicationRun(id, PublicationStatus.STORED, publishedAt, vocabularies, diagrams[0],
                    complete[0], includeDrafts);
        } catch (IOException | RuntimeException e) {
            log.error("Publication {} of {} failed; no files stored", id, publishedAt, e);
            failQuietly(id, summary(e));
            throw new PublicationFailedException(FAILED, e);
        } catch (Error e) {
            log.error("Publication {} of {} failed; no files stored", id, publishedAt, e);
            failQuietly(id, summary(e));
            throw e;
        } finally {
            if (dir != null) {
                deleteQuietly(dir);
            }
        }
    }

    private void failQuietly(long id, String error) {
        try {
            archive.fail(id, error);
        } catch (RuntimeException e) {
            log.error("Publication {}: could not record the failure; the run stays RUNNING until restart", id, e);
        }
    }

    /** The root cause in one line, for the history; the stack trace goes to the log only. */
    static String summary(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message.strip());
    }

    private static BuiltFile writeFile(Path dir, String name, Consumer<OutputStream> writer) {
        Path path = dir.resolve(name);
        MessageDigest sha256 = sha256();
        try (OutputStream out = new DigestOutputStream(Files.newOutputStream(path), sha256)) {
            writer.accept(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            return new BuiltFile(name, path, Files.size(path), "\"" + HexFormat.of().formatHex(sha256.digest()) + "\"");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        } catch (IOException e) {
            log.warn("Publication: could not remove the temporary build {}", dir, e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
