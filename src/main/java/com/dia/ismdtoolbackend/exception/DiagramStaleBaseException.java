package com.dia.ismdtoolbackend.exception;

/**
 * Thrown at materialize when the concept an overlay targets was edited after the overlay was staged (409).
 *
 * <p>The fingerprint is stamped once, when the overlay comes into existence, and never refreshed while it
 * stays staged — refreshing it on every save would absorb the concurrent edit instead of reporting it.
 * Recovery is therefore discard-then-restage, not re-sending the same values.
 *
 * <p>Reported per change in {@code MaterializeResultDto.failed[]} rather than as a response status, so one
 * stale concept does not fail the whole Převzít.
 */
public class DiagramStaleBaseException extends RuntimeException {

    /** Stable code, echoed in the per-change failure entry. */
    public static final String ERROR_CODE = "STALE_BASE";

    public DiagramStaleBaseException(String message) {
        super(message);
    }
}
