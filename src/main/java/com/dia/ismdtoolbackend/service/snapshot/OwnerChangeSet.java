package com.dia.ismdtoolbackend.service.snapshot;

import org.apache.jena.rdf.model.Statement;

import java.util.HashSet;
import java.util.Set;

/**
 * Mutable accumulator of the RDF triple delta the snapshot service produces for the <em>owning</em>
 * concept's outbox aggregate (C1). The snapshot service contributes into this; the caller (edit hook
 * / local-copy endpoints) owns the single owner-keyed {@code OutboxWriter.enqueueUpsert} flush.
 *
 * <p>Composes with {@code ConceptEditor.EditResult}: the edit hook wraps that result's
 * {@code statementsToRemove}/{@code statementsToAdd} via {@link #of(Set, Set)} so the materialized
 * copy and the owner's link edit land in one aggregate.
 */
public final class OwnerChangeSet {

    public final Set<Statement> toRemove;
    public final Set<Statement> toAdd;

    public OwnerChangeSet() {
        this(new HashSet<>(), new HashSet<>());
    }

    private OwnerChangeSet(Set<Statement> toRemove, Set<Statement> toAdd) {
        this.toRemove = toRemove;
        this.toAdd = toAdd;
    }

    /** Wraps existing change-set sets (e.g. {@code EditResult.statementsToRemove/Add}). */
    public static OwnerChangeSet of(Set<Statement> toRemove, Set<Statement> toAdd) {
        return new OwnerChangeSet(toRemove, toAdd);
    }
}
