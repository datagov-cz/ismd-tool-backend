package com.dia.ismdtoolbackend.enums;

/**
 * The materialized (Převzít) operation a staged overlay resolves to. Closed set, shared across the
 * overlay stage, the materialize result, and the FE. See {@code docs/DIAGRAM_LAYER_API.md}.
 */
public enum DiagramOp {

    /** Swap a VZTAH's {@code domain} ⇄ {@code range}. */
    SWAP_DIRECTION,

    /** Move a hierarchy link from one class to another (two edits, all-or-nothing). */
    FLIP_HIERARCHY,

    /** Clear {@code subClassOf} and populate {@code exactMatch} (or reverse). */
    CHANGE_HIERARCHY_TYPE,

    /** Repoint a VLASTNOST's {@code domain} to a different owning class. */
    CHANGE_PROPERTY_PARENT,

    /** Fill a domainless VLASTNOST's {@code domain}. */
    SET_PROPERTY_DOMAIN,

    /** Add a broader link on the target class, then delete the VZTAH (two calls, all-or-nothing). */
    CONVERT_TO_HIERARCHY
}
