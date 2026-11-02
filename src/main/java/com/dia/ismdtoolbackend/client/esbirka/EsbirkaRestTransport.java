package com.dia.ismdtoolbackend.client.esbirka;

import com.dia.ismdtoolbackend.config.EsbirkaConfig;
import com.dia.ismdtoolbackend.exception.EsbirkaDocumentNotFoundException;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.utility.sparql.SparqlCircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * HTTP transport for the e-Sbírka REST API: API key header, gzip, the process-wide
 * {@link EsbirkaRateGate}, retries, a circuit breaker and the per-request {@link EsbirkaDeadline}.
 *
 * <ul>
 *   <li>Retried: 429, non-JSON 5xx (edge HTML pages) and connection failures. Every attempt passes
 *       the rate gate again.</li>
 *   <li>Breaker counts 5xx, call timeouts and connection failures. Not-found, 429 (also when
 *       retries run out), our own request errors, a bad API key and an exhausted deadline never
 *       count — they say nothing about whether the API is up.</li>
 *   <li>Not-found ({@code DOKUMENT_NENALEZEN} etc., HTTP 400) → {@link EsbirkaDocumentNotFoundException};
 *       every other failure → {@link SparqlEndpointUnavailableException} (503).</li>
 * </ul>
 * The API key is sent only as a header and appears in no log line or exception message.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "esbirka.provider", havingValue = "rest")
class EsbirkaRestTransport {

    static final String API_KEY_HEADER = "esel-api-access-key";
    private static final String LABEL = "e-Sbírka";
    private static final String AZURE_REF_HEADER = "x-azure-ref";
    private static final String CALLS_METRIC = "esbirka.rest.calls";
    private static final String DURATION_METRIC = "esbirka.rest.duration";
    private static final Duration MIN_CALL_TIMEOUT = Duration.ofMillis(1);
    /** Read timeout of the call running on this thread; see {@link DeadlineAwareRequestFactory}. */
    private static final ThreadLocal<Duration> CALL_TIMEOUT = new ThreadLocal<>();

    private final String baseUrl;
    private final String apiKey;
    private final Duration callTimeout;
    private final Duration requestDeadline;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final EsbirkaRateGate gate;
    private final SparqlCircuitBreaker breaker;
    private final MeterRegistry meterRegistry;

    EsbirkaRestTransport(EsbirkaConfig config, JsonMapper jsonMapper, MeterRegistry meterRegistry) {
        EsbirkaConfig.Rest rest = config.getRest();
        this.baseUrl = stripTrailingSlash(rest.getBaseUrl());
        this.apiKey = rest.getApiKey() == null ? "" : rest.getApiKey().trim();
        this.callTimeout = Duration.ofMillis(rest.getTimeout());
        this.requestDeadline = Duration.ofMillis(rest.getRequestDeadlineMs());
        this.maxAttempts = Math.max(1, rest.getRetry().getMaxAttempts());
        this.initialBackoffMs = rest.getRetry().getInitialBackoffMs();
        this.jsonMapper = jsonMapper;
        this.meterRegistry = meterRegistry;
        this.gate = new EsbirkaRateGate(rest.getMinRequestSpacingMs(), rest.getMaxInFlight());
        this.breaker = new SparqlCircuitBreaker(LABEL,
                rest.getCircuitBreaker().getFailureThreshold(), rest.getCircuitBreaker().getCooldownMs());
        // HTTP/1.1 keep-alive pool, as used for the rate-limit measurements; connections stay
        // bounded by max-in-flight.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(callTimeout)
                .build();
        this.restClient = RestClient.builder()
                .requestFactory(new DeadlineAwareRequestFactory(httpClient, callTimeout))
                .configureMessageConverters(converters -> converters.withJsonConverter(
                        new JacksonJsonHttpMessageConverter(jsonMapper)))
                .build();
        if (apiKey.isEmpty()) {
            log.warn("e-Sbírka REST provider is active but esbirka.rest.api-key is blank; /api/eli/* will answer 503.");
        }
    }

    /** A fresh budget for one {@code /api/eli} request. */
    EsbirkaDeadline newDeadline() {
        return EsbirkaDeadline.after(requestDeadline);
    }

    /** Absolute URI of an already-encoded API path, e.g. {@code /dokumenty-sbirky/%2Fsb%2F1997%2F49}. */
    URI uri(String encodedPathAndQuery) {
        return URI.create(baseUrl + encodedPathAndQuery);
    }

    /**
     * @param operation low-cardinality metric tag, e.g. {@code historie}
     */
    <T> T get(String operation, URI uri, Class<T> responseType, EsbirkaDeadline deadline) {
        return execute(operation, HttpMethod.GET, uri, null, responseType, deadline);
    }

    <T> T post(URI uri, Object body, Class<T> responseType, EsbirkaDeadline deadline) {
        return execute("search", HttpMethod.POST, uri, body, responseType, deadline);
    }

    private <T> T execute(String operation, HttpMethod method, URI uri, Object body,
                          Class<T> responseType, EsbirkaDeadline deadline) {
        if (apiKey.isEmpty()) {
            throw new SparqlEndpointUnavailableException(LABEL, "e-Sbírka API key is not configured (esbirka.rest.api-key)");
        }
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            byte[] json = breaker.call(() -> attemptWithRetries(operation, method, uri, body, deadline));
            return parse(json, responseType, operation, uri);
        } catch (NotCounted e) {
            throw e.failure;
        } finally {
            sample.stop(meterRegistry.timer(DURATION_METRIC, "operation", operation));
        }
    }

    private byte[] attemptWithRetries(String operation, HttpMethod method, URI uri, Object body,
                                      EsbirkaDeadline deadline) {
        Attempt last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (last != null && !backOff(attempt, deadline)) {
                break;
            }
            last = attemptOnce(operation, method, uri, body, deadline);
            if (last.body() != null) {
                return last.body();
            }
            log.debug("e-Sbírka {} {} attempt {}/{} failed: {}",
                    operation, uri.getRawPath(), attempt, maxAttempts, last.failure().getMessage());
        }
        log.warn("e-Sbírka {} {} failed after retries: {}", operation, uri.getRawPath(), Objects.requireNonNull(last).failure().getMessage());
        if (last.rateLimited()) {
            throw new NotCounted(last.failure());
        }
        throw last.failure();
    }

    /** Returns the body, a retryable failure, or throws a final one. */
    private Attempt attemptOnce(String operation, HttpMethod method, URI uri, Object body, EsbirkaDeadline deadline) {
        acquireGate(operation, uri, deadline);
        Response response;
        Duration timeout = deadline.remaining();
        boolean cutByDeadline = timeout.compareTo(callTimeout) < 0;
        try {
            if (!cutByDeadline) {
                timeout = callTimeout;
            } else if (timeout.compareTo(MIN_CALL_TIMEOUT) < 0) {
                throw deadlineExceeded(operation, uri, "no time left for the call");
            }
            response = exchange(method, uri, body, timeout);
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) {
                count(operation, "timeout");
                if (cutByDeadline) {
                    throw deadlineExceeded(operation, uri, "call cut at the request deadline");
                }
                throw new SparqlEndpointUnavailableException(LABEL,
                        "e-Sbírka " + operation + " timed out after " + timeout.toMillis() + " ms", e);
            }
            count(operation, "io_error");
            return Attempt.retry(new SparqlEndpointUnavailableException(LABEL,
                    "e-Sbírka " + operation + " connection failure: " + e.getMessage(), e), false);
        } finally {
            gate.release();
        }
        return handle(operation, uri, response);
    }

    private Attempt handle(String operation, URI uri, Response response) {
        int status = response.status();
        if (status == 429) {
            count(operation, "rate_limited");
            return Attempt.retry(unavailable(operation, response, "rate limited (429)"), true);
        }
        boolean json = isJson(response.contentType());
        if (status >= 200 && status < 300 && json) {
            count(operation, "success");
            return Attempt.success(response.body());
        }
        EsbirkaRestErrors.Classification error = json
                ? EsbirkaRestErrors.classify(response.body(), jsonMapper)
                : new EsbirkaRestErrors.Classification(EsbirkaRestErrors.Kind.NOT_API_ERROR, null, null, null);
        return switch (error.kind()) {
            case NOT_FOUND -> {
                count(operation, "not_found");
                throw new EsbirkaDocumentNotFoundException(
                        "Dokument e-Sbírky nebyl nalezen (" + error.kod() + "): " + uri.getRawPath());
            }
            case INVALID_REQUEST -> {
                count(operation, "invalid_request");
                logApiError("e-Sbírka rejected our request", operation, uri, response, error);
                throw new NotCounted(unavailable(operation, response, "request rejected: " + error.kod()));
            }
            case INVALID_API_KEY -> {
                count(operation, "invalid_api_key");
                logApiError("e-Sbírka API key missing/invalid", operation, uri, response, error);
                throw new NotCounted(unavailable(operation, response, "API key missing or invalid"));
            }
            case OTHER -> {
                if (status >= 500) {
                    count(operation, "server_error");
                    logApiError("e-Sbírka server error", operation, uri, response, error);
                    throw unavailable(operation, response, "server error " + status + " " + error.kod());
                }
                count(operation, "client_error");
                logApiError("e-Sbírka client error", operation, uri, response, error);
                throw new NotCounted(unavailable(operation, response, "client error " + status + " " + error.kod()));
            }
            case NOT_API_ERROR -> {
                if (status >= 500 || (status >= 200 && status < 300)) {
                    count(operation, "server_error");
                    yield Attempt.retry(unavailable(operation, response, "non-JSON response " + status), false);
                }
                count(operation, "client_error");
                log.error("e-Sbírka {} {} answered {} without an API error body (x-azure-ref={})",
                        operation, uri.getRawPath(), status, response.azureRef());
                throw new NotCounted(unavailable(operation, response, "non-JSON response " + status));
            }
        };
    }

    private void acquireGate(String operation, URI uri, EsbirkaDeadline deadline) {
        try {
            gate.acquire(deadline);
        } catch (EsbirkaRateGate.DeadlineExceeded e) {
            throw deadlineExceeded(operation, uri, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotCounted(new SparqlEndpointUnavailableException(LABEL,
                    "interrupted waiting for the e-Sbírka rate gate"));
        }
    }

    /** Sleeps before {@code attempt}; false when the deadline leaves no room for it. */
    private boolean backOff(int attempt, EsbirkaDeadline deadline) {
        long delayMs = initialBackoffMs * (1L << Math.min(attempt - 2, 20))
                + ThreadLocalRandom.current().nextLong(Math.max(1, initialBackoffMs));
        if (deadline.remainingNanos() <= TimeUnit.MILLISECONDS.toNanos(delayMs)) {
            return false;
        }
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Response exchange(HttpMethod method, URI uri, Object body, Duration timeout) {
        CALL_TIMEOUT.set(timeout);
        try {
            RestClient.RequestBodySpec spec = restClient.method(method).uri(uri)
                    .header(API_KEY_HEADER, apiKey)
                    .accept(MediaType.APPLICATION_JSON);
            if (body != null) {
                spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return spec.exchange((request, response) -> new Response(
                    response.getStatusCode().value(),
                    response.getHeaders().getContentType(),
                    response.getHeaders().getFirst(AZURE_REF_HEADER),
                    response.getBody().readAllBytes()), true);
        } finally {
            CALL_TIMEOUT.remove();
        }
    }

    private <T> T parse(byte[] json, Class<T> responseType, String operation, URI uri) {
        try {
            return jsonMapper.readValue(json, responseType);
        } catch (JacksonException e) {
            log.error("e-Sbírka {} {} returned JSON that does not match {}: {}",
                    operation, uri.getRawPath(), responseType.getSimpleName(), e.getOriginalMessage());
            throw new SparqlEndpointUnavailableException(LABEL, "unparseable e-Sbírka " + operation + " response", e);
        }
    }

    private NotCounted deadlineExceeded(String operation, URI uri, String reason) {
        log.warn("e-Sbírka {} {} gave up at the {} ms request deadline: {}",
                operation, uri.getRawPath(), requestDeadline.toMillis(), reason);
        return new NotCounted(new SparqlEndpointUnavailableException(LABEL,
                "e-Sbírka request deadline exceeded (" + reason + ")"));
    }

    private static SparqlEndpointUnavailableException unavailable(String operation, Response response, String reason) {
        return new SparqlEndpointUnavailableException(LABEL,
                "e-Sbírka " + operation + " " + reason + " (x-azure-ref=" + response.azureRef() + ")");
    }

    private static void logApiError(String what, String operation, URI uri, Response response,
                                    EsbirkaRestErrors.Classification error) {
        log.error("{}: {} {} → {} {} \"{}\" (correlationId={}, x-azure-ref={})", what, operation, uri.getRawPath(),
                response.status(), error.kod(), error.popis(), error.correlationId(), response.azureRef());
    }

    private void count(String operation, String outcome) {
        meterRegistry.counter(CALLS_METRIC, "operation", operation, "outcome", outcome).increment();
    }

    private static boolean isJson(MediaType contentType) {
        return contentType != null && (MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                || "json".equalsIgnoreCase(contentType.getSubtypeSuffix()));
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private record Response(int status, MediaType contentType, String azureRef, byte[] body) {
    }

    /** {@code body} on success; otherwise a failure worth retrying. */
    private record Attempt(byte[] body, SparqlEndpointUnavailableException failure, boolean rateLimited) {
        static Attempt success(byte[] body) {
            return new Attempt(body, null, false);
        }

        static Attempt retry(SparqlEndpointUnavailableException failure, boolean rateLimited) {
            return new Attempt(null, failure, rateLimited);
        }
    }

    /** Carries a 503 through the circuit breaker without counting it as an outage. */
    private static final class NotCounted extends RuntimeException {
        private final SparqlEndpointUnavailableException failure;

        NotCounted(SparqlEndpointUnavailableException failure) {
            super(failure.getMessage(), failure, false, false);
            this.failure = failure;
        }
    }

    /**
     * Shares one pooled {@link HttpClient} and applies the read timeout of the current call, so a
     * call is cut to the remaining request deadline. {@link JdkClientHttpRequestFactory} is a thin
     * wrapper, cheap to create per request.
     */
    private static final class DeadlineAwareRequestFactory implements ClientHttpRequestFactory {
        private final HttpClient httpClient;
        private final Duration defaultTimeout;
        private final Executor bodyExecutor;

        DeadlineAwareRequestFactory(HttpClient httpClient, Duration defaultTimeout) {
            this.httpClient = httpClient;
            this.defaultTimeout = defaultTimeout;
            this.bodyExecutor = new SimpleAsyncTaskExecutor("esbirka-rest-body-");
        }

        /** {@code @NonNull} restates the nullness of the {@code @NullMarked} interface. */
        @Override
        @NonNull
        public ClientHttpRequest createRequest(@NonNull URI uri, @NonNull HttpMethod httpMethod) throws IOException {
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient, bodyExecutor);
            Duration timeout = CALL_TIMEOUT.get();
            factory.setReadTimeout(timeout != null ? timeout : defaultTimeout);
            factory.enableCompression(true);
            return factory.createRequest(uri, httpMethod);
        }
    }
}
