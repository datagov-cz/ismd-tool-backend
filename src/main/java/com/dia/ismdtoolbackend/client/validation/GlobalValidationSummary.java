package com.dia.ismdtoolbackend.client.validation;

/** Outcome of the validator's global (corpus) pass: status plus a user-facing Czech message. */
public record GlobalValidationSummary(GlobalValidationStatus status, String message) {
}
