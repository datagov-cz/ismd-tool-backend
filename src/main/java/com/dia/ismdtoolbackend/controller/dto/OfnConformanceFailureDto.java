package com.dia.ismdtoolbackend.controller.dto;

import java.util.List;

/**
 * Body of the OFN conformance errors ({@code ONTOLOGY_DOWNLOAD_BLOCKED_BY_OFN},
 * {@code UPLOAD_OFN_NONCONFORMANT}, {@code OFN_WRITE_NONCONFORMANT}): what to correct.
 *
 * @param vocabularyIri the vocabulary checked
 * @param issues        every problem found, none omitted
 */
public record OfnConformanceFailureDto(String vocabularyIri, List<OfnConformanceIssueDto> issues) {
}
