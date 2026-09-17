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

    /** {@code rdfs:domain} of a VZTAH or VLASTNOST. */
    private String domain;

    /** {@code rdfs:range} of a VZTAH. */
    private String range;

    /** {@code subClassOf} targets of a TRIDA. */
    private List<String> broaderConcept;

    /** {@code skos:exactMatch} targets. */
    private List<String> exactMatch;

    /** Op 6 marker converting this VZTAH into a hierarchy; null otherwise. */
    private ConvertToHierarchy convertToHierarchy;

    /** The referenced concept's {@code updatedAt} at stage time: Převzít's stale-base fingerprint. */
    private LocalDateTime baseUpdatedAt;

    /** Adds {@code broader} as a super-class of {@code addBroaderOn}, then deletes the overlay's VZTAH. */
    @Data
    public static class ConvertToHierarchy {
        private String addBroaderOn;
        private String broader;
    }
}
