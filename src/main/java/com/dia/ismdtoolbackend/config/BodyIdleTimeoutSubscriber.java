package com.dia.ismdtoolbackend.config;

import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Fails a response body whose server goes quiet. The clock runs only while the reader has asked for bytes and
 * the server has not sent them, so a slow reader never trips it and a long body that keeps streaming is not
 * cut off. On expiry the exchange is cancelled and the reader gets an {@link HttpTimeoutException}.
 */
final class BodyIdleTimeoutSubscriber<T> implements HttpResponse.BodySubscriber<T> {

    private final HttpResponse.BodySubscriber<T> downstream;
    private final Duration idleTimeout;
    private final ScheduledExecutorService scheduler;

    private Flow.Subscription upstream;
    private long outstanding;
    private ScheduledFuture<?> deadline;
    private boolean terminated;

    BodyIdleTimeoutSubscriber(HttpResponse.BodySubscriber<T> downstream, Duration idleTimeout,
                              ScheduledExecutorService scheduler) {
        this.downstream = downstream;
        this.idleTimeout = idleTimeout;
        this.scheduler = scheduler;
    }

    @Override
    public CompletionStage<T> getBody() {
        return downstream.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        synchronized (this) {
            upstream = subscription;
        }
        downstream.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
                synchronized (BodyIdleTimeoutSubscriber.this) {
                    if (terminated) {
                        return;
                    }
                    outstanding = n == Long.MAX_VALUE || outstanding + n < 0 ? Long.MAX_VALUE : outstanding + n;
                    armIfWaiting();
                }
                subscription.request(n);
            }

            @Override
            public void cancel() {
                synchronized (BodyIdleTimeoutSubscriber.this) {
                    terminated = true;
                    disarm();
                }
                subscription.cancel();
            }
        });
    }

    @Override
    public void onNext(List<ByteBuffer> item) {
        synchronized (this) {
            if (terminated) {
                return;
            }
            if (outstanding != Long.MAX_VALUE) {
                outstanding--;
            }
            disarm();
            armIfWaiting();
            downstream.onNext(item);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        synchronized (this) {
            if (terminated) {
                return;
            }
            terminated = true;
            disarm();
            downstream.onError(throwable);
        }
    }

    @Override
    public void onComplete() {
        synchronized (this) {
            if (terminated) {
                return;
            }
            terminated = true;
            disarm();
            downstream.onComplete();
        }
    }

    private void expire() {
        Flow.Subscription toCancel;
        synchronized (this) {
            if (terminated) {
                return;
            }
            terminated = true;
            deadline = null;
            toCancel = upstream;
            downstream.onError(new HttpTimeoutException(
                    "Response body stalled for " + idleTimeout.toMillis() + " ms"));
        }
        toCancel.cancel();
    }

    private void armIfWaiting() {
        if (outstanding > 0 && deadline == null) {
            deadline = scheduler.schedule(this::expire, idleTimeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void disarm() {
        if (deadline != null) {
            deadline.cancel(false);
            deadline = null;
        }
    }
}
