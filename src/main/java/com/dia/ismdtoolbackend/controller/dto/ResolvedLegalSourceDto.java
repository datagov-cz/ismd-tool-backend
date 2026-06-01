package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.utility.eli.ParsedEli;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
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
    private List<ParsedEli.FragmentSegment> fragmentSegments;
    private String displayLabel;

    private String fragmentCitation;
    private String fragmentBodyHtml;
    private LocalDate versionValidUntil;
    private Boolean isLatestVersion;

    private EnrichmentStatus enrichmentStatus;

    public enum EnrichmentStatus {
        OK,
        PENDING,
        UNAVAILABLE,
        NOT_FOUND,
        SKIPPED_NON_FRAGMENT,
        INVALID_IRI
    }
}
