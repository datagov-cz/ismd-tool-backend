package com.dia.ismdtoolbackend.utility.sparql;

import com.dia.ismdtoolbackend.exception.JenaTDB2Exception;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.sparql.engine.http.QueryExceptionHTTP;

import java.net.HttpURLConnection;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Executes operations against an internal Fuseki / TDB2 store, applying:
 * <ul>
 *   <li>semaphore-bounded concurrency (so a slow consumer can't exhaust the pool),</li>
 *   <li>fixed acquire timeout that fails fast with a "server is busy" message rather
 *       than blocking forever,</li>
 *   <li>{@link RDFConnection} lifecycle (build → run → close) via try-with-resources,</li>
 *   <li>uniform exception cascade: HTTP / Jena errors → {@link JenaTDB2Exception} with
 *       a per-call Czech user message, while {@link JenaTDB2Exception}s thrown by the
 *       operation pass through unwrapped.</li>
 * </ul>
 *
 * <p>The 13 public methods on {@code JenaTDB2Repository} each used to inline this
 * pattern with a distinct Czech message. The repository now provides the connection
 * factory and the per-method message; this class owns the rest.
 */
@Slf4j
public final class FusekiSparqlExecutor {

    private final Semaphore semaphore;
    private final int semaphoreTimeoutMs;
    private final Supplier<RDFConnection> connectionFactory;

    public FusekiSparqlExecutor(Semaphore semaphore,
                                int semaphoreTimeoutMs,
                                Supplier<RDFConnection> connectionFactory) {
        this.semaphore = semaphore;
        this.semaphoreTimeoutMs = semaphoreTimeoutMs;
        this.connectionFactory = connectionFactory;
    }

    /**
     * Run {@code operation} under a semaphore-acquired connection and return its result.
     *
     * @param operationLabel        short description woven into log messages on failure
     *                              (e.g. {@code "saving concept to graph " + name}).
     *                              Internal — the user never sees this.
     * @param userFacingErrorMessage Czech message used as {@link JenaTDB2Exception#getMessage()}
     *                               when the operation or connection fails. Surfaced to the
     *                               caller via {@link com.dia.ismdtoolbackend.config.GlobalExceptionHandler}.
     * @param operation             the Fuseki operation to run; receives an open
     *                              {@link RDFConnection} that will be closed on return.
     */
    public <T> T execute(String operationLabel,
                         String userFacingErrorMessage,
                         Function<RDFConnection, T> operation) {
        acquireOrThrow(userFacingErrorMessage);
        try (RDFConnection conn = connectionFactory.get()) {
            return operation.apply(conn);
        } catch (QueryExceptionHTTP | HttpException e) {
            throw mapHttpFailure(operationLabel, userFacingErrorMessage, e);
        } catch (JenaTDB2Exception e) {
            // Operation already wrapped its own failure (e.g. via a nested executor call);
            // pass through so the inner message survives.
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error during {}: {}", operationLabel, e.getMessage(), e);
            throw new JenaTDB2Exception(userFacingErrorMessage, e);
        } finally {
            semaphore.release();
        }
    }

    /**
     * Void-returning variant of {@link #execute(String, String, Function)}.
     */
    public void executeVoid(String operationLabel,
                            String userFacingErrorMessage,
                            Consumer<RDFConnection> operation) {
        execute(operationLabel, userFacingErrorMessage, conn -> {
            operation.accept(conn);
            return null;
        });
    }

    private void acquireOrThrow(String userFacingErrorMessage) {
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(semaphoreTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JenaTDB2Exception("Interrupted while waiting for Fuseki connection", e);
        }
        if (!acquired) {
            log.warn("Fuseki semaphore acquisition timed out after {}ms, available permits: {}",
                    semaphoreTimeoutMs, semaphore.availablePermits());
            throw new JenaTDB2Exception("Fuseki server is busy, try again later");
        }
    }

    /**
     * Translate a Jena HTTP failure into the right domain exception so the status code
     * survives to the frontend instead of collapsing into a blanket HTTP 500:
     * <ul>
     *   <li><b>404</b> — the graph genuinely isn't there → {@link OntologyNotFoundException} (HTTP 404).</li>
     *   <li><b>502/503/504</b> and connection-level failures (status {@code <= 0}, how Jena
     *       reports a dropped/reset connection) — Fuseki is unreachable, not the data missing →
     *       {@link SparqlEndpointUnavailableException} (HTTP 503), which the outbox treats as transient.</li>
     *   <li>everything else → {@link JenaTDB2Exception} (HTTP 500), the original behaviour.</li>
     * </ul>
     */
    private RuntimeException mapHttpFailure(String operationLabel, String userFacingErrorMessage, Exception e) {
        int status = statusCodeOf(e);
        if (status == HttpURLConnection.HTTP_NOT_FOUND) {
            log.info("Fuseki returned 404 (graph not found) during {}: {}", operationLabel, e.getMessage());
            return new OntologyNotFoundException("Slovník nebyl nalezen.", e);
        }
        if (status <= 0
                || status == HttpURLConnection.HTTP_BAD_GATEWAY
                || status == HttpURLConnection.HTTP_UNAVAILABLE
                || status == HttpURLConnection.HTTP_GATEWAY_TIMEOUT) {
            log.error("Fuseki unavailable (status {}) during {}: {}", status, operationLabel, e.getMessage());
            return new SparqlEndpointUnavailableException("Fuseki", userFacingErrorMessage, e);
        }
        log.error("Fuseki HTTP error (status {}) during {}: {}", status, operationLabel, e.getMessage());
        return new JenaTDB2Exception(userFacingErrorMessage, e);
    }

    /** Both {@link HttpException} and {@link QueryExceptionHTTP} expose {@code getStatusCode()}, with no common supertype. */
    private static int statusCodeOf(Exception e) {
        if (e instanceof HttpException he) {
            return he.getStatusCode();
        }
        if (e instanceof QueryExceptionHTTP qe) {
            return qe.getStatusCode();
        }
        return 0;
    }
}
