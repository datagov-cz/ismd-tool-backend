package com.dia.ismdtoolbackend.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Full content of one znění of a law resolved from a number/year reference (e.g. "49/1997") —
 * the latest znění by default, or the one the caller selected. Carries the law/version header,
 * the full version list and the rendered fragment tree in one response.
 *
 * <p>The header fields ({@link #versionIri}, {@link #versionEliPath}, {@link #versionDate},
 * {@link #versionLatest}) always describe the znění rendered in {@link #fragments}.
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

    /** Whether the rendered version is the law's current znění (má-poslední-znění). */
    private boolean versionLatest;

    /** All versions of this law, newest first; for an FE version switcher. */
    private List<LawVersionDto> versions;

    /** Rendered fragment tree of {@link #versionIri}, each node carrying its HTML body. */
    private List<FragmentDto> fragments;

    /**
     * The whole version as one HTML body: a document-ordered, nested tree of
     * {@code <section data-eli=… data-kind=…>} wrappers, each holding its fragment's HTML body
     * followed by its children. {@link #fragments} carries the same content for navigation.
     */
    private String bodyHtml;
}