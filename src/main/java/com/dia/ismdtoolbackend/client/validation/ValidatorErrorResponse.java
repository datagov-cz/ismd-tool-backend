package com.dia.ismdtoolbackend.client.validation;

/**
 * The validator's 4xx/5xx error body — its {@code ApiResponseDto}:
 * {@code {data, message, success, errorCode}}. Only the fields the tool reads are mapped; the rest
 * are ignored. {@code errorCode} is kept as a plain string because the validator's error codes are
 * its own enum, not the tool's {@code ErrorCode}.
 *
 * @param message   human-readable reason, surfaced to the user.
 * @param errorCode the validator's error code name, e.g. {@code "UNSUPPORTED_FORMAT"}.
 */
record ValidatorErrorResponse(String message, String errorCode) {
}