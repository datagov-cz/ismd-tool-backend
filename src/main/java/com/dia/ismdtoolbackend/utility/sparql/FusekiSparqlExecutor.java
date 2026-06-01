package com.dia.ismdtoolbackend.utility.sparql;

import com.dia.ismdtoolbackend.exception.JenaTDB2Exception;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.sparql.engine.http.QueryExceptionHTTP;

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
            log.error("Fuseki HTTP error during {}: {}", operationLabel, e.getMessage());
            throw new JenaTDB2Exception(userFacingErrorMessage, e);
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
}
