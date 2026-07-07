package com.dia.ismdtoolbackend.utility.sparql;

import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTPBuilder;

import java.net.http.HttpClient;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Executes SPARQL queries against an external HTTP endpoint, applying:
 * <ul>
 *   <li>endpoint-empty pre-check that fails fast with the endpoint's
 *       {@link SparqlEndpointUnavailableException} so misconfiguration surfaces
 *       as a clear 503 instead of a downstream NPE,</li>
 *   <li>configured timeout on every call,</li>
 *   <li>uniform exception wrapping via {@link SparqlExceptionMapper#strict}.</li>
 * </ul>
 *
 * <p>Instantiated per-endpoint by each SPARQL client (Esbirka, RPP, NKD). Plain
 * Java class, not a Spring bean — clients inject their {@code @ConfigurationProperties}
 * and build their executor in the constructor. Two executors for the same
 * endpoint never need to coexist, so the per-bean shape would be ceremony.
 */
@Slf4j
public final class HttpSparqlExecutor {

    @Getter
    private final String endpointLabel;
    private final String endpointUrl;
    private final int timeoutMs;
    private final HttpClient httpClient;

    /**
     * @param endpointLabel  short human-readable name (e.g. {@code "e-Sbírka"}).
     *                       Surfaced in exception messages and the global handler's
     *                       Czech response, so spell it the way you want a user to
     *                       see it.
     * @param endpointUrl    the SPARQL endpoint URL; may be blank/null at construction
     *                       time (Spring {@code @Value} default), in which case calls
     *                       fail fast with a clear "not configured" exception.
     * @param timeoutMs      per-query timeout in milliseconds.
     * @param httpClient     shared, pooled {@link HttpClient} (see
     *                       {@code ExternalSparqlConfig}). May be {@code null}, in which
     *                       case Jena falls back to a per-call client — kept nullable so
     *                       tests and any legacy caller construct without wiring a client.
     */
    public HttpSparqlExecutor(String endpointLabel, String endpointUrl, int timeoutMs,
                              HttpClient httpClient) {
        this.endpointLabel = endpointLabel;
        this.endpointUrl = endpointUrl;
        this.timeoutMs = timeoutMs;
        this.httpClient = httpClient;
    }

    /**
     * Convenience constructor without a shared client — Jena builds a per-call client.
     * Prefer {@link #HttpSparqlExecutor(String, String, int, HttpClient)} in production so
     * connections are pooled across round-trips.
     */
    public HttpSparqlExecutor(String endpointLabel, String endpointUrl, int timeoutMs) {
        this(endpointLabel, endpointUrl, timeoutMs, null);
    }

    private QueryExecutionHTTPBuilder service() {
        QueryExecutionHTTPBuilder builder = QueryExecutionHTTPBuilder.service(endpointUrl);
        return httpClient != null ? builder.httpClient(httpClient) : builder;
    }

    /**
     * Run a SELECT, mapping the {@link ResultSet} with {@code mapper}. The mapper
     * is invoked while the {@link QueryExecution} is still open, so streaming
     * mappers are safe.
     */
    public <T> T select(String operationLabel, String query, Function<ResultSet, T> mapper) {
        requireConfigured();
        return SparqlExceptionMapper.strict(
                operationLabel,
                SparqlEndpointUnavailableException.class,
                () -> {
                    try (QueryExecution qe = service()
                            .query(query)
                            .timeout(timeoutMs, TimeUnit.MILLISECONDS)
                            .build()) {
                        return mapper.apply(qe.execSelect());
                    }
                },
                (msg, cause) -> new SparqlEndpointUnavailableException(endpointLabel, msg, cause));
    }

    /**
     * Run a CONSTRUCT and return the result {@link Model}. Empty/null result models
     * are returned as {@link Optional#empty()}.
     */
    public Optional<Model> construct(String operationLabel, String query) {
        requireConfigured();
        return SparqlExceptionMapper.strict(
                operationLabel,
                SparqlEndpointUnavailableException.class,
                () -> {
                    Model model = service()
                            .query(query)
                            .timeout(timeoutMs, TimeUnit.MILLISECONDS)
                            .construct();
                    return (model == null || model.isEmpty()) ? Optional.empty() : Optional.of(model);
                },
                (msg, cause) -> new SparqlEndpointUnavailableException(endpointLabel, msg, cause));
    }

    /**
     * Run a CONSTRUCT in lenient mode: any failure logs a warning and returns
     * {@link Optional#empty()}. For best-effort lookups (e.g. NKD publication
     * checks) where a failed query mustn't break the surrounding flow.
     */
    public Optional<Model> constructLenient(String operationLabel, String query) {
        if (!isConfigured()) {
            log.warn("{} endpoint not configured, skipping {}", endpointLabel, operationLabel);
            return Optional.empty();
        }
        return SparqlExceptionMapper.lenient(
                operationLabel,
                () -> {
                    Model model = service()
                            .query(query)
                            .timeout(timeoutMs, TimeUnit.MILLISECONDS)
                            .construct();
                    return (model == null || model.isEmpty()) ? Optional.empty() : Optional.of(model);
                },
                Optional.empty());
    }

    public boolean isConfigured() {
        return endpointUrl != null && !endpointUrl.trim().isEmpty();
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new SparqlEndpointUnavailableException(
                    endpointLabel, endpointLabel + " endpoint not configured");
        }
    }
}
