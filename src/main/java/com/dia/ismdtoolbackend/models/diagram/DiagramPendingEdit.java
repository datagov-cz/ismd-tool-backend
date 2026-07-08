package com.dia.ismdtoolbackend.models.diagram;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Staged structural overlay diff over a real concept, serialized to {@code pending_edit_json}. All
 * references are concept IRIs. See {@code docs/DIAGRAM_LAYER.md}.
 */
@Data
public class DiagramPendingEdit {

    /** {@code rdfs:domain} — VZTAH or VLASTNOST. */
    private String domain;

    /** {@code rdfs:range} — VZTAH. */
    private String range;

    /** {@code subClassOf} targets — TRIDA. */
    private List<String> broaderConcept;

    /** {@code subPropertyOf} targets — VLASTNOST. */
    private List<String> superProperty;

    /** {@code subPropertyOf} targets — VZTAH. */
    private List<String> superRelation;

    /** {@code skos:exactMatch} targets. */
    private List<String> exactMatch;

    /** Op 6 marker — convert this VZTAH into a hierarchy. Null otherwise. */
    private ConvertToHierarchy convertToHierarchy;

    /** Referenced concept's {@code updatedAt} at stage time — stale-base fingerprint for Převzít. */
    private LocalDateTime baseUpdatedAt;

    /** Add {@code broader} as a super-class of {@code addBroaderOn}, then delete the overlay's VZTAH. */
    @Data
    public static class ConvertToHierarchy {
        private String addBroaderOn;
        private String broader;
    }
}
