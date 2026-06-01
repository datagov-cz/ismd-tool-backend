package com.dia.ismdtoolbackend.utility.sparql;

import lombok.extern.slf4j.Slf4j;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.sparql.engine.http.QueryExceptionHTTP;

import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Centralizes the SPARQL exception cascade that every SPARQL client / repository
 * in this codebase used to inline. Two failure modes are exposed:
 * <ul>
 *   <li>{@link #strict(String, Class, Supplier, BiFunction)} — wraps Jena's HTTP /
 *       generic exceptions into a domain exception so the controller layer maps
 *       them to a proper HTTP status. Used by external endpoints (Esbirka, RPP)
 *       and the Fuseki repository where a failure must surface to the caller.</li>
 *   <li>{@link #lenient(String, Supplier, Object)} — logs a warning and returns
 *       the supplied fallback. Used for best-effort operations (NKD publication
 *       checks) where a failed lookup must not break the surrounding flow.</li>
 * </ul>
 * The {@code label} is a short human-readable description (e.g. {@code "law search"})
 * woven into both the log line and any wrapped exception's message so operators can
 * trace which call failed without parsing stack traces.
 */
@Slf4j
public final class SparqlExceptionMapper {

    private SparqlExceptionMapper() {
    }

    /**
     * Run {@code action}; on Jena HTTP failure or any other exception, wrap with
     * {@code wrapper.apply(message, cause)} and rethrow.
     *
     * <p>Domain exceptions of type {@code domainExceptionType} thrown <em>by the
     * action itself</em> (e.g. an unavailable exception from a
     * {@code requireEndpoint()} pre-check) propagate untouched — we only re-wrap
     * if the caught exception isn't already the target type. This avoids
     * "mapping failed: e-Sbírka endpoint not configured" double-wraps.
     */
    public static <T, E extends RuntimeException> T strict(
            String label,
            Class<E> domainExceptionType,
            Supplier<T> action,
            BiFunction<String, Throwable, E> wrapper) {
        try {
            return action.get();
        } catch (QueryExceptionHTTP | HttpException e) {
            throw wrapper.apply(label + " fetch failed: " + e.getMessage(), e);
        } catch (Exception e) {
            if (domainExceptionType.isInstance(e)) {
                throw domainExceptionType.cast(e);
            }
            throw wrapper.apply(label + " mapping failed: " + e.getMessage(), e);
        }
    }

    /**
     * Run {@code action}; on any exception log a warning and return {@code fallback}.
     * For best-effort operations where a failure shouldn't break the surrounding flow.
     */
    public static <T> T lenient(String label, Supplier<T> action, T fallback) {
        try {
            return action.get();
        } catch (QueryExceptionHTTP e) {
            log.warn("SPARQL error during {}: {}", label, e.getMessage());
            return fallback;
        } catch (HttpException e) {
            log.warn("HTTP error during {}: {}", label, e.getMessage());
            return fallback;
        } catch (Exception e) {
            log.warn("Unexpected error during {}: {}", label, e.getMessage());
            return fallback;
        }
    }
}
