package com.dia.ismdtoolbackend.enums;

/**
 * The materialized operation a staged overlay resolves to. Closed set, shared across the
 * overlay stage, the materialize result, and the FE. See {@code docs/DIAGRAM_LAYER_API.md}.
 */
public enum DiagramOp {

    /** Swap a VZTAH's {@code domain} ⇄ {@code range}. */
    SWAP_DIRECTION,

    /**
     * Change a class's hierarchy: alter its {@code subClassOf} list or move it between {@code subClassOf}
     * and {@code exactMatch}. A flip (B⊐A → A⊐B) is two of these on two nodes, each applied independently.
     */
    CHANGE_HIERARCHY_TYPE,

    /**
     * Set or repoint a VLASTNOST's {@code domain}. Covers both filling a domainless property and moving one
     * to a different owning class — indistinguishable from the overlay alone (both carry only a domain), so
     * both report as this op.
     */
    CHANGE_PROPERTY_PARENT,

    /** Add a broader link on the target class, then delete the VZTAH (two calls, all-or-nothing). */
    CONVERT_TO_HIERARCHY
}
