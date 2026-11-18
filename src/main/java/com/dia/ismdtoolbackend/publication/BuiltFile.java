package com.dia.ismdtoolbackend.publication;

import java.nio.file.Path;

/**
 * One file a publication run has built, before it is stored.
 *
 * @param etag strong ETag value, the quoted SHA-256 of the content
 */
public record BuiltFile(String name, Path path, long size, String etag) {
}
