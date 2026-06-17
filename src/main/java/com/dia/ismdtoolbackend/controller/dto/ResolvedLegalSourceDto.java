package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.utility.eli.ParsedEli;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Stable response contract: every scalar key is always serialized (explicit
 * {@code null} when the value is unknown for the current {@link EnrichmentStatus}),
 * and {@code fragmentSegments} is always a list — empty, never null/absent.
 * The set of populated scalars depends on {@link #enrichmentStatus}; consumers
 * should null-check, not presence-check.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolvedLegalSourceDto {

    private String originalUrl;
    private String eliPath;
    private String domain;
    private ParsedEli.Level level;
    private String lawIri;
    private String versionIri;
    private String fragmentIri;
    private String lawNumber;
    private Integer lawYear;
    private String sbirkaCode;
    private LocalDate versionDate;

    @Builder.Default
    private List<ParsedEli.FragmentSegment> fragmentSegments = List.of();

    private String displayLabel;

    private String fragmentCitation;
    private String fragmentBodyHtml;
    private LocalDate versionValidUntil;
    private Boolean isLatestVersion;

    private EnrichmentStatus enrichmentStatus;

    /** Never null — coerces a null backing list (e.g. from a non-fragment parse) to empty. */
    public List<ParsedEli.FragmentSegment> getFragmentSegments() {
        return fragmentSegments == null ? List.of() : fragmentSegments;
    }

    public enum EnrichmentStatus {
        OK,
        PENDING,
        UNAVAILABLE,
        NOT_FOUND,
        SKIPPED_NON_FRAGMENT,
        INVALID_IRI
    }
}
