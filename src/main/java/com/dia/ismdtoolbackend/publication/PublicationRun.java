package com.dia.ismdtoolbackend.publication;

import com.dia.ismdtoolbackend.publication.archive.PublicationStatus;

import java.time.Instant;

/**
 * One publication run, as reported to the admin who started it.
 *
 * @param id            the publication, for its history entry and for downloading its files later
 * @param status        {@code RUNNING} when the run was only started, {@code STORED} once it is done
 * @param publishedAt   the publication's {@code publikováno}
 * @param complete      false when at least one vocabulary's content could not be read; false while running
 * @param includeDrafts the {@code ismd.publication.include-drafts} the run uses
 */
public record PublicationRun(long id, PublicationStatus status, Instant publishedAt, int vocabularies, int diagrams,
                             boolean complete, boolean includeDrafts) {

    static PublicationRun running(long id, Instant publishedAt, boolean includeDrafts) {
        return new PublicationRun(id, PublicationStatus.RUNNING, publishedAt, 0, 0, false, includeDrafts);
    }
}
