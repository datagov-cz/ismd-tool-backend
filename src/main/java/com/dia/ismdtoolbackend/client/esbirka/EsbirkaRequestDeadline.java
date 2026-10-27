package com.dia.ismdtoolbackend.client.esbirka;

import java.util.Optional;

/**
 * The deadline of the {@code /api/eli} request running on this thread, set by
 * {@link EsbirkaDeadlineFilter}. Absent outside such a request; callers then start a fresh one.
 */
final class EsbirkaRequestDeadline {

    private static final ThreadLocal<EsbirkaDeadline> CURRENT = new ThreadLocal<>();

    private EsbirkaRequestDeadline() {
    }

    static void begin(EsbirkaDeadline deadline) {
        CURRENT.set(deadline);
    }

    static void end() {
        CURRENT.remove();
    }

    static Optional<EsbirkaDeadline> current() {
        return Optional.ofNullable(CURRENT.get());
    }
}
