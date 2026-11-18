package com.dia.ismdtoolbackend.publication.archive;

import java.time.Instant;
import java.util.List;

/**
 * One stored publication as the admin history lists it; never carries file content.
 *
 * @param files the publication's files; {@code stored} is false once the content is past the retention
 */
public record PublicationSummary(long id, Instant publishedAt, String triggeredBy, boolean includeDrafts,
                                 boolean complete, int vocabularies, int diagrams, String contextUrl,
                                 List<FileSummary> files) {

    public record FileSummary(String name, long size, String etag, boolean stored) {
    }
}
