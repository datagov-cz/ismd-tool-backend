package com.dia.ismdtoolbackend.publication.archive;

import java.time.Instant;
import java.util.List;

/**
 * One publication run as the admin history lists it; never carries file content.
 *
 * @param error why a {@code FAILED} run failed; null otherwise
 * @param files the publication's files, none unless {@code STORED}; {@code stored} is false once the
 *              content is past the retention
 */
public record PublicationSummary(long id, PublicationStatus status, Instant publishedAt, String triggeredBy,
                                 boolean includeDrafts, boolean complete, int vocabularies, int diagrams,
                                 String contextUrl, String error, List<FileSummary> files) {

    public record FileSummary(String name, long size, String etag, boolean stored) {
    }
}
