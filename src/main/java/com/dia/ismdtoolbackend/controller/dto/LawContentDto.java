package com.dia.ismdtoolbackend.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Full content of a law resolved from a number/year reference (e.g. "49/1997").
 *
 * <p>Carries the resolved law/version header so the FE can label the document
 * ("Zákon č. 49/1997 Sb., znění od 1. 11. 2025") and offer a version switcher
 * ({@link #versions}) without a second call, plus the rendered fragment tree
 * ({@link #fragments}) with per-node HTML bodies for in-document browsing.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LawContentDto {

    /** Resolved law IRI, e.g. .../eli/cz/sb/1997/49 */
    private String lawIri;

    /** Official citation of the law, e.g. "49/1997 Sb." */
    private String citace;

    /** IRI of the version whose content is in {@link #fragments} (the latest version). */
    private String versionIri;

    /** ELI path of that version, e.g. /eli/cz/sb/1997/49/2025-11-01 */
    private String versionEliPath;

    /** Effective-from date of the rendered version. */
    private LocalDate versionDate;

    /** All versions of this law, newest first; for an FE version switcher. */
    private List<LawVersionDto> versions;

    /** Rendered fragment tree of {@link #versionIri}, each node carrying its HTML body. */
    private List<FragmentDto> fragments;

    /**
     * The whole version assembled into one coherent HTML body server-side: a document-ordered,
     * nested tree of {@code <section data-eli=… data-kind=…>} wrappers, each containing its
     * fragment's HTML body (when any) followed by its children. Lets the FE render the full law
     * directly; {@link #fragments} remains available for tree-based navigation.
     */
    private String bodyHtml;
}