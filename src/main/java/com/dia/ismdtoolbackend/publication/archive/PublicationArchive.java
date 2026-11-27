package com.dia.ismdtoolbackend.publication.archive;

import com.dia.ismdtoolbackend.exception.PublicationFilePrunedException;
import com.dia.ismdtoolbackend.publication.BuiltFile;
import com.dia.ismdtoolbackend.publication.PublicationConfig;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The publications in PostgreSQL: the audit trail of every run and the files an admin downloads. A run
 * is recorded {@code RUNNING} when it starts and ends {@code STORED} with its files or {@code FAILED}
 * without. Metadata is kept forever, file content for the last {@code ismd.publication.retain-files}
 * stored publications.
 */
@Service
@RequiredArgsConstructor
public class PublicationArchive {

    private static final int GZIP_BUFFER = 8192;

    private final PublicationRepository publications;
    private final PublicationFileRepository files;
    private final PublicationConfig config;

    /**
     * A stored file with its content as stored, gzipped.
     *
     * @param size length of the decompressed content
     */
    public record ArchivedFile(String name, long size, String etag, byte[] gzip) {

        /** The decompressed content, read as it is consumed. */
        public InputStream openContent() {
            try {
                return new GZIPInputStream(new ByteArrayInputStream(gzip), GZIP_BUFFER);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** What a run records when it starts. */
    public record RunStart(Instant publishedAt, String triggeredBy, boolean includeDrafts, String contextUrl) {
    }

    /** What a finished run records besides its files. */
    public record RunResult(boolean complete, int vocabularies, int diagrams) {
    }

    /** Records a {@code RUNNING} run. */
    @Transactional
    public long begin(RunStart run) {
        PublicationEntity entity = new PublicationEntity();
        entity.setStatus(PublicationStatus.RUNNING);
        entity.setPublishedAt(run.publishedAt());
        entity.setTriggeredBy(run.triggeredBy());
        entity.setIncludeDrafts(run.includeDrafts());
        entity.setContextUrl(run.contextUrl());
        return publications.saveAndFlush(entity).getId();
    }

    /** Stores a built run with its files as {@code STORED}, then drops file content outside the retention. */
    @Transactional
    public void store(long id, RunResult result, Map<String, BuiltFile> built) {
        PublicationEntity entity = publications.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Publikace " + id + " neexistuje."));
        entity.setStatus(PublicationStatus.STORED);
        entity.setError(null);
        entity.setComplete(result.complete());
        entity.setVocabularies(result.vocabularies());
        entity.setDiagrams(result.diagrams());
        for (BuiltFile f : built.values()) {
            PublicationFileEntity file = new PublicationFileEntity();
            file.setName(f.name());
            file.setSize(f.size());
            file.setEtag(f.etag());
            file.setContentGzip(gzip(f));
            entity.addFile(file);
        }
        publications.saveAndFlush(entity);
        List<Long> keep = publications.findNewestStoredIds(PageRequest.of(0, Math.max(1, config.getRetainFiles())));
        files.clearContentExcept(keep);
    }

    /** Records a run as {@code FAILED}; {@code error} is cut to the column length. */
    @Transactional
    public void fail(long id, String error) {
        publications.findById(id).ifPresent(entity -> {
            entity.setStatus(PublicationStatus.FAILED);
            entity.setError(truncate(error));
        });
    }

    /** Fails every {@code RUNNING} run; called on startup, when no run of this instance can be running. */
    @Transactional
    public int failInterrupted(String error) {
        return publications.failRunning(truncate(error));
    }

    /** Publication runs, newest first, without file content. */
    @Transactional(readOnly = true)
    public List<PublicationSummary> history(int page, int size) {
        List<PublicationEntity> page0 = publications.findAll(PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.desc("id")))).getContent();
        if (page0.isEmpty()) {
            return List.of();
        }
        Map<Long, List<PublicationSummary.FileSummary>> filesById = new HashMap<>();
        for (PublicationFileSummaryRow f : files.findSummaries(page0.stream().map(PublicationEntity::getId).toList())) {
            filesById.computeIfAbsent(f.publicationId(), id -> new ArrayList<>())
                    .add(new PublicationSummary.FileSummary(f.name(), f.size(), f.etag(), f.stored()));
        }
        return page0.stream().map(p -> new PublicationSummary(p.getId(), p.getStatus(), p.getPublishedAt(),
                p.getTriggeredBy(), p.isIncludeDrafts(), p.isComplete(), p.getVocabularies(), p.getDiagrams(),
                p.getContextUrl(), p.getError(), filesById.getOrDefault(p.getId(), List.of()))).toList();
    }

    /**
     * One stored file, gzipped as stored.
     *
     * @throws EntityNotFoundException        when the publication or the file does not exist
     * @throws PublicationFilePrunedException when its content is past the retention
     */
    @Transactional(readOnly = true)
    public ArchivedFile file(long publicationId, String name) {
        PublicationFileEntity f = files.findByPublicationIdAndName(publicationId, name)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Publikace " + publicationId + " neobsahuje soubor " + name + "."));
        if (f.getContentGzip() == null) {
            throw new PublicationFilePrunedException("Obsah souboru " + name + " publikace " + publicationId
                    + " už není uložen (uchovává se posledních " + config.getRetainFiles() + " publikací).");
        }
        return new ArchivedFile(f.getName(), f.getSize(), f.getEtag(), f.getContentGzip());
    }

    private static String truncate(String error) {
        return error == null || error.length() <= PublicationEntity.ERROR_LENGTH
                ? error : error.substring(0, PublicationEntity.ERROR_LENGTH);
    }

    private static byte[] gzip(BuiltFile f) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream out = new GZIPOutputStream(bytes); InputStream in = Files.newInputStream(f.path())) {
            in.transferTo(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
