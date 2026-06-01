package com.dia.ismdtoolbackend.controller.dto;

/**
 * Wire shape for a property datatype codelist entry.
 * Intentionally omits the underlying IRI — the FE works in terms of
 * stable {@code code} values and renders {@code label} for users.
 */
public record DataTypeDto(String code, String label) {}
