package com.dia.ismdtoolbackend.publication;

import java.nio.file.Path;
import java.time.Instant;

/**
 * One published file as served: its location, size and content hash.
 *
 * @param etag        strong ETag value, the quoted SHA-256 of the content
 * @param publishedAt the publication's {@code publikováno}, used as {@code Last-Modified}
 */
public record PublishedFile(String name, Path path, long size, String etag, Instant publishedAt) {
}
