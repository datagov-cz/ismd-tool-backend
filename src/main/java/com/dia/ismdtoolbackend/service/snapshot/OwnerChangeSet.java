package com.dia.ismdtoolbackend.service.snapshot;

import org.apache.jena.rdf.model.Statement;

import java.util.HashSet;
import java.util.Set;

/**
 * Mutable accumulator of the RDF triple delta the snapshot service produces for the owning concept's
 * outbox aggregate. The snapshot service contributes into this; the caller (edit hook / local-copy
 * endpoints) unions it with the owner's own edit delta and performs the single owner-keyed
 * {@code OutboxWriter.enqueueUpsert} flush, so the copy and the link land in one aggregate.
 */
public final class OwnerChangeSet {
    public final Set<Statement> toRemove = new HashSet<>();
    public final Set<Statement> toAdd = new HashSet<>();
}
