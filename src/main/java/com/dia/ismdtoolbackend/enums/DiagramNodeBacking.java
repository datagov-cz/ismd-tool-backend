package com.dia.ismdtoolbackend.enums;

/**
 * What a diagram node stands for — the switch that decides who owns its content.
 *
 * <p>The diagram layer never owns concept content. A node is therefore one of two kinds: a reference to
 * a real ISMD concept (content lives in PG/RDF, read live on load), or a draft sketch (content owned by
 * the diagram until promoted). See {@code DiagramNodeEntity}.
 */
public enum DiagramNodeBacking {

    /**
     * The node references a real ISMD concept by its IRI. The diagram stores only the reference and
     * layout; content is re-read live from the ontology on load. Edits to the concept flow through the
     * existing {@code /api/concept} CRUD, never this entity.
     */
    ISMD_CONCEPT,

    /**
     * The node is a draft with no IRI yet — a sketch the diagram is the sole owner of. Its concept-shaped
     * content lives in {@code draftJson}. Promoting it materializes a real concept and flips the node to
     * {@link #ISMD_CONCEPT}, after which the diagram no longer owns its content.
     */
    DRAFT
}