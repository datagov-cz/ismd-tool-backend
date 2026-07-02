package com.dia.ismdtoolbackend.enums;

/**
 * User-driven action available on a tracked NKD local copy, surfaced in {@code LinkSnapshotDto.availableActions}
 * and derived from the snapshot's deviation status:
 * <ul>
 *   <li>{@code HAS_DEVIATIONS} → {@code [UPDATE, REMOVE]}</li>
 *   <li>{@code NO_DEVIATION}   → {@code [REMOVE]}</li>
 *   <li>{@code ENDPOINT_UNAVAILABLE} / {@code QUERY_ERROR} → {@code []} (comparison untrusted)</li>
 * </ul>
 * ({@code CONCEPT_NOT_FOUND_IN_NKD} is never surfaced — it removes the snapshot server-side.)
 */
public enum SnapshotAction {

    /** Re-snapshot the NKD concept and re-materialize the local copy (accept upstream changes). */
    UPDATE,

    /** Drop the link to the NKD concept and remove the local copy. */
    REMOVE
}