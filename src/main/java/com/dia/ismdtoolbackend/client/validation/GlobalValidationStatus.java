package com.dia.ismdtoolbackend.client.validation;

/**
 * Whether the validator's corpus-aware (global) checks ran. Mirrors the validator's enum; lets a
 * client tell "no conflicts with NKD" from "NKD was never checked".
 */
public enum GlobalValidationStatus {
    /** Every corpus lookup succeeded. */
    OK,
    /** Not attempted: no corpus endpoint configured, or no global rule enabled. */
    SKIPPED,
    /** Attempted, but at least one corpus lookup failed; global findings may be missing. */
    UNAVAILABLE
}
