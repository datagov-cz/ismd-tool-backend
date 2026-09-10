package com.dia.ismdtoolbackend.enums;

/**
 * The materialized operation a staged overlay resolves to. A closed set shared by the overlay stage, the
 * materialize result and the FE. See {@code docs/DIAGRAM_LAYER_API.md}.
 */
public enum DiagramOp {

    /** Swaps a VZTAH's {@code domain} ⇄ {@code range}. */
    SWAP_DIRECTION,

    /**
     * Changes a class's hierarchy: its {@code subClassOf} list, or a move between {@code subClassOf} and
     * {@code exactMatch}. A flip (B⊐A → A⊐B) is two of these on two nodes, applied independently.
     */
    CHANGE_HIERARCHY_TYPE,

    /**
     * Sets or repoints a VLASTNOST's {@code domain}, covering both a domainless property being filled and a
     * move to another owning class. The two are indistinguishable from the overlay alone, both carrying
     * only a domain, so both report as this op.
     */
    CHANGE_PROPERTY_PARENT,

    /** Adds a broader link on the target class, then deletes the VZTAH; all-or-nothing. */
    CONVERT_TO_HIERARCHY
}
