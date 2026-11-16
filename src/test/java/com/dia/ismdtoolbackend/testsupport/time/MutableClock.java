package com.dia.ismdtoolbackend.testsupport.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** UTC test clock that moves only when the test moves it. Safe to read from several threads. */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;

    private MutableClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    public static MutableClock of(Instant start) {
        return new MutableClock(start);
    }

    public static MutableClock of(String isoInstant) {
        return of(Instant.parse(isoInstant));
    }

    public void advance(Duration by) {
        now.updateAndGet(t -> t.plus(by));
    }

    public void advanceMillis(long millis) {
        advance(Duration.ofMillis(millis));
    }

    public void set(Instant to) {
        now.set(to);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
