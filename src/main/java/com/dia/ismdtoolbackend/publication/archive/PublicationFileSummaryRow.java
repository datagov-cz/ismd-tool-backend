package com.dia.ismdtoolbackend.publication.archive;

/** A file row without its content, as {@link PublicationFileRepository#findSummaries} projects it. */
public record PublicationFileSummaryRow(Long publicationId, String name, long size, String etag, boolean stored) {
}
