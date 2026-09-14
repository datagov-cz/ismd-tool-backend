package com.dia.ismdtoolbackend.controller.dto;

/**
 * One blocking validation error, flattened for display. Mirrors the fields of
 * {@code com.dia.validation.ValidationResult} that identify a problem to a user; severity is
 * omitted because every entry here is an error.
 *
 * @param ruleName     the validation rule that failed
 * @param message      human-readable description of the problem
 * @param focusNodeUri IRI of the offending resource, null when the rule is not node-scoped
 * @param focusNodeName display name of the offending resource, null when unavailable
 * @param resultPathUri IRI of the offending property, null when the rule is not path-scoped
 */
public record ValidationErrorSummaryDto(
        String ruleName,
        String message,
        String focusNodeUri,
        String focusNodeName,
        String resultPathUri
) {
}