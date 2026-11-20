package com.dia.ismdtoolbackend.audit;

/** What a {@link DeleteAuditEntry} records the deletion of. */
public enum DeletedEntityType {
    ONTOLOGY,
    CONCEPT,
    LOCAL_COPY,
    COMMENT,
    DIAGRAM
}
