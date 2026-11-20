package com.dia.ismdtoolbackend.config;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * An {@link HttpClient} that gives every request without its own timeout a default one. The JDK client has
 * no client-wide request timeout, and Jena builds each Fuseki request itself, so this is the one place a
 * limit applies to queries, updates and graph reads and writes alike.
 *
 * <p>The JDK timeout covers the wait for the response headers. A response that stalls while its body
 * streams is not cut off.
 */
final class RequestTimeoutHttpClient extends HttpClient {

    private final HttpClient delegate;
    private final Duration timeout;

    RequestTimeoutHttpClient(HttpClient delegate, Duration timeout) {
        this.delegate = delegate;
        this.timeout = timeout;
    }

    HttpRequest withDefaultTimeout(HttpRequest request) {
        if (request.timeout().isPresent()) {
            return request;
        }
        return HttpRequest.newBuilder(request, (name, value) -> true).timeout(timeout).build();
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        return delegate.send(withDefaultTimeout(request), handler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                            HttpResponse.BodyHandler<T> handler) {
        return delegate.sendAsync(withDefaultTimeout(request), handler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                            HttpResponse.BodyHandler<T> handler,
                                                            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        return delegate.sendAsync(withDefaultTimeout(request), handler, pushPromiseHandler);
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return delegate.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
        return delegate.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return delegate.proxy();
    }

    @Override
    public SSLContext sslContext() {
        return delegate.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return delegate.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return delegate.authenticator();
    }

    @Override
    public Version version() {
        return delegate.version();
    }

    @Override
    public Optional<Executor> executor() {
        return delegate.executor();
    }
}
