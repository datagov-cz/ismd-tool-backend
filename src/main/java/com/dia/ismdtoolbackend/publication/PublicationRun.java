package com.dia.ismdtoolbackend.publication;

import java.time.Instant;

/**
 * One committed publication, as reported to the admin who started it.
 *
 * @param id            the stored publication, for downloading its files later
 * @param publishedAt   the publication's {@code publikováno}
 * @param complete      false when at least one vocabulary's content could not be read
 * @param includeDrafts the {@code ismd.publication.include-drafts} the run used
 */
public record PublicationRun(long id, Instant publishedAt, int vocabularies, int diagrams, boolean complete,
                             boolean includeDrafts) {
}
