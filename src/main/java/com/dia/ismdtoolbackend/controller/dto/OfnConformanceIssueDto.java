package com.dia.ismdtoolbackend.controller.dto;

/**
 * One OFN conformance problem.
 *
 * @param kind       the issue kind, e.g. {@code VALUE_DROPPED} or {@code CONCEPT_INCOMPLETE}
 * @param conceptIri the affected concept, null for the vocabulary itself
 * @param location   path in the OFN document, e.g. {@code pojmy[řidič]/agenda}
 * @param message    the reason, in Czech
 * @param value      the offending value, null when a whole field or object is meant
 */
public record OfnConformanceIssueDto(String kind, String conceptIri, String location, String message, String value) {
}
