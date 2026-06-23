package com.dia.ismdtoolbackend.outbox.dto;

import java.time.Instant;

/**
 * Health snapshot of the outbox queue for the admin endpoint: how many rows sit in each state and
 * how old the oldest unprocessed row is (a rising {@code oldestPendingCreatedAt} age is the signal
 * that the relay is stuck or disabled).
 *
 * @param pending                 count of PENDING rows (awaiting / retrying apply)
 * @param failed                  count of FAILED rows (exhausted retries or bad payload — blocking their aggregate)
 * @param done                    count of DONE rows still within the retention window
 * @param oldestPendingCreatedAt  creation time of the oldest PENDING row, or null if none pending
 */
public record OutboxStatusDto(
        long pending,
        long failed,
        long done,
        Instant oldestPendingCreatedAt
) {
}
