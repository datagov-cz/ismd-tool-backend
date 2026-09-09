package com.dia.ismdtoolbackend.models.diagram;

import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;

/**
 * Whether a placed canvas element's backing concept is live, deleted or unreadable — the single verdict every
 * element type is rendered from, whatever its shape.
 *
 * <p><b>One three-valued verdict, not two booleans.</b> {@code stale} and {@code unavailable} are different
 * absences that must never both be true, an invariant that used to live in a comment beside four separate
 * copies of {@code detail == null && !unavailable}. Here it is a property of the type: a {@link Presence} has
 * exactly one value, so the pair cannot be set inconsistently.
 *
 * <p><b>Staleness is about RDF only.</b> An overlay is staged work, not yet written, so it never makes
 * anything stale — every RDF write goes through the outbox and is external by construction. A concept whose
 * triples are gone is {@link Presence#STALE} whether or not an overlay targets it.
 *
 * <p>The layout may diverge from RDF: a placed element whose backing was deleted underneath the canvas keeps
 * rendering, flagged, rather than vanishing without the user's knowledge. Only materialization treats the
 * divergence as a conflict. See {@code docs/DIAGRAM_LAYER_API.md}.
 */
public record Backing(Presence presence, ConceptDetailModel detail) {

    /** The three states a placed element's backing can be in. */
    public enum Presence {

        /** The concept was read and is present; {@link Backing#detail} is non-null. */
        LIVE,

        /** The graph was read and the concept is not in it — deleted underneath the canvas. */
        STALE,

        /**
         * The concept's graph could not be read at all, so it is presumed intact. Only ever a foreign
         * concept: an unreadable own graph fails the whole request with a 502.
         */
        UNAVAILABLE
    }

    /** The concept was deleted underneath the canvas. */
    public boolean stale() {
        return presence == Presence.STALE;
    }

    /** The concept's graph could not be read; it is presumed intact. */
    public boolean unavailable() {
        return presence == Presence.UNAVAILABLE;
    }

    /** The concept's live content, or null when it is stale or unavailable. */
    public ConceptDetailModel detailOrNull() {
        return detail;
    }
}