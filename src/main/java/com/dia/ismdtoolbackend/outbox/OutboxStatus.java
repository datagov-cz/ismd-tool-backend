package com.dia.ismdtoolbackend.outbox;

/**
 * Lifecycle state of an {@link OutboxEntry}.
 *
 * <p>{@code PENDING} → {@code DONE} on a successful TDB2 apply; {@code PENDING} → {@code FAILED}
 * once {@code attempts} reaches the configured cap. A {@code FAILED} row blocks its aggregate's
 * later rows until an admin retries it (resets to {@code PENDING}).
 */
public enum OutboxStatus {
    PENDING,
    DONE,
    FAILED
}
