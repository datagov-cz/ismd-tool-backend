package com.dia.ismdtoolbackend.publication.file;

import java.time.Instant;

/**
 * What both files of one publication share.
 *
 * @param publishedAt the instant the run started, identical in both files
 * @param iriBase     base of the IRIs ISMD mints, ending in {@code /}
 */
public record PublicationHeader(Instant publishedAt, boolean includesDrafts, String contextUrl, String iriBase) {
}
