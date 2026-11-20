package com.dia.ismdtoolbackend.models;

/** Order of the ontology list. Each ends on the id, so equal keys still page stably. */
public enum OntologyListSort {
    /** By the Czech name (any name, then the slug, when it has none), A→Z. */
    NAME,
    /** Most recently updated first. */
    UPDATED
}
