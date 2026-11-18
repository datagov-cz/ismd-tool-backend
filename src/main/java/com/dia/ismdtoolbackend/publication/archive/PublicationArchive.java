package com.dia.ismdtoolbackend.publication.archive;

import com.dia.ismdtoolbackend.exception.PublicationFilePrunedException;
import com.dia.ismdtoolbackend.publication.PublicationConfig;
import com.dia.ismdtoolbackend.publication.BuiltFile;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * The publications in PostgreSQL: the audit trail of every run and the files an admin downloads. Metadata
 * is kept forever, file content for the last {@code ismd.publication.retain-files} publications.
 */
@Service
@RequiredArgsConstructor
public class PublicationArchive {

    private final PublicationRepository publications;
    private final PublicationFileRepository files;
    private final PublicationConfig config;

    /** A stored file with its content decompressed. */
    public record ArchivedFile(String name, long size, String etag, byte[] content) {
    }

    /** What a run records besides its files. */
    public record RunRecord(Instant publishedAt, String triggeredBy, boolean includeDrafts, boolean complete,
                            int vocabularies, int diagrams, String contextUrl) {
    }

    /** Stores a built publication with its files, then drops file content outside the retention. */
    @Transactional
    public long save(RunRecord run, Map<String, BuiltFile> built) {
        PublicationEntity entity = new PublicationEntity();
        entity.setPublishedAt(run.publishedAt());
        entity.setTriggeredBy(run.triggeredBy());
        entity.setIncludeDrafts(run.includeDrafts());
        entity.setComplete(run.complete());
        entity.setVocabularies(run.vocabularies());
        entity.setDiagrams(run.diagrams());
        entity.setContextUrl(run.contextUrl());
        for (BuiltFile f : built.values()) {
            PublicationFileEntity file = new PublicationFileEntity();
            file.setName(f.name());
            file.setSize(f.size());
            file.setEtag(f.etag());
            file.setContentGzip(gzip(f));
            entity.addFile(file);
        }
        long id = publications.saveAndFlush(entity).getId();
        List<Long> keep = publications.findNewestIds(PageRequest.of(0, Math.max(1, config.getRetainFiles())));
        files.clearContentExcept(keep);
        return id;
    }

    /** Stored publications, newest first, without file content. */
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
        return page0.stream().map(p -> new PublicationSummary(p.getId(), p.getPublishedAt(), p.getTriggeredBy(),
                p.isIncludeDrafts(), p.isComplete(), p.getVocabularies(), p.getDiagrams(), p.getContextUrl(),
                filesById.getOrDefault(p.getId(), List.of()))).toList();
    }

    /**
     * One stored file.
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
        return archived(f);
    }

    private static ArchivedFile archived(PublicationFileEntity f) {
        return new ArchivedFile(f.getName(), f.getSize(), f.getEtag(), gunzip(f.getContentGzip()));
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

    private static byte[] gunzip(byte[] gzip) {
        try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(gzip))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
