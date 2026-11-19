package com.dia.ismdtoolbackend.models;

/** A stored vocabulary as services address it; never a request or response body. */
public record OntologyRef(Long id, String slug, String graphName, Boolean isPublished) {
}
