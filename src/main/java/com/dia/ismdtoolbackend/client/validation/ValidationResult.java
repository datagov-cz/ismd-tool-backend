package com.dia.ismdtoolbackend.client.validation;

import com.dia.ismdtoolbackend.utility.UtilityMethods;

/**
 * A single validation finding.
 *
 * @param nkdResource for global (corpus) findings only: the published-corpus resource that
 *                    triggered the finding, for consumer navigation. {@code null} on local
 *                    (SHACL) findings.
 */
public record ValidationResult(ValidationSeverity severity, String message, String ruleName, String focusNodeUri,
                               String resultPathUri, String value, NkdResource nkdResource) {

    /** Local findings carry no corpus resource. */
    public ValidationResult(ValidationSeverity severity, String message, String ruleName, String focusNodeUri,
                            String resultPathUri, String value) {
        this(severity, message, ruleName, focusNodeUri, resultPathUri, value, null);
    }

    public boolean isError() {
        return severity == ValidationSeverity.ERROR;
    }

    public boolean isWarning() {
        return severity == ValidationSeverity.WARNING;
    }

    public boolean isInfo() {
        return severity == ValidationSeverity.INFO;
    }

    public String getFocusNodeName() {
        return UtilityMethods.extractNameFromIRI(focusNodeUri);
    }

    @Override
    public String toString() {
        return String.format(
                "ValidationResult{severity=%s, rule='%s', message='%s', focusNode='%s'}",
                severity,
                ruleName,
                message,
                getFocusNodeName()
        );
    }
}