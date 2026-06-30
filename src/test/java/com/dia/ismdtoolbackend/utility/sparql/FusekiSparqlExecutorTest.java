package com.dia.ismdtoolbackend.utility.sparql;

import com.dia.ismdtoolbackend.exception.JenaTDB2Exception;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.sparql.engine.http.QueryExceptionHTTP;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.Semaphore;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that {@link FusekiSparqlExecutor} maps Jena HTTP failures to the right
 * domain exception so the Fuseki status code survives to the frontend:
 * 404 → {@link OntologyNotFoundException} (HTTP 404),
 * 502/503/504 + connection drops → {@link SparqlEndpointUnavailableException} (HTTP 503),
 * everything else → {@link JenaTDB2Exception} (HTTP 500).
 *
 * <p>This is the regression guard for the dev incident where a missing graph (Fuseki 404)
 * was reported to the FE as a blanket 500, indistinguishable from a real Fuseki outage.
 */
class FusekiSparqlExecutorTest {

    private static final String LABEL = "fetching graph X";
    private static final String USER_MSG = "Failed to fetch graph";

    /** Executor backed by a mock connection and a semaphore with permits to spare so acquire never blocks. */
    private FusekiSparqlExecutor executorThrowing(RuntimeException ignored) {
        RDFConnection conn = Mockito.mock(RDFConnection.class);
        return new FusekiSparqlExecutor(new Semaphore(4), 1_000, () -> conn);
    }

    /** Runs an operation that always raises {@code toThrow}, exercising the executor's HTTP catch/mapping. */
    private <T> T run(FusekiSparqlExecutor executor, RuntimeException toThrow) {
        Function<RDFConnection, T> op = conn -> {
            throw toThrow;
        };
        return executor.execute(LABEL, USER_MSG, op);
    }

    @Test
    void httpException404_mapsToOntologyNotFound() {
        HttpException notFound = new HttpException(404, "Not Found", "graph absent");
        FusekiSparqlExecutor executor = executorThrowing(notFound);

        OntologyNotFoundException ex = assertThrows(OntologyNotFoundException.class,
                () -> run(executor, notFound));
        assertSame(notFound, ex.getCause());
    }

    @Test
    void httpException503_mapsToEndpointUnavailable() {
        HttpException unavailable = new HttpException(503, "Service Unavailable", "down");
        FusekiSparqlExecutor executor = executorThrowing(unavailable);

        SparqlEndpointUnavailableException ex = assertThrows(SparqlEndpointUnavailableException.class,
                () -> run(executor, unavailable));
        assertEquals("Fuseki", ex.getEndpointLabel());
        assertSame(unavailable, ex.getCause());
    }

    @Test
    void httpException502_mapsToEndpointUnavailable() {
        HttpException badGateway = new HttpException(502, "Bad Gateway", "proxy");
        FusekiSparqlExecutor executor = executorThrowing(badGateway);

        assertThrows(SparqlEndpointUnavailableException.class, () -> run(executor, badGateway));
    }

    @Test
    void httpException504_mapsToEndpointUnavailable() {
        HttpException gatewayTimeout = new HttpException(504, "Gateway Timeout", "slow");
        FusekiSparqlExecutor executor = executorThrowing(gatewayTimeout);

        assertThrows(SparqlEndpointUnavailableException.class, () -> run(executor, gatewayTimeout));
    }

    @Test
    void connectionDrop_unsetStatus_mapsToEndpointUnavailable() {
        // A reset/dropped connection is how Jena reports the `content-length 62 != 0 written`
        // tear-down — no HTTP status, getStatusCode() <= 0.
        HttpException dropped = new HttpException("Connection reset");
        FusekiSparqlExecutor executor = executorThrowing(dropped);

        assertThrows(SparqlEndpointUnavailableException.class, () -> run(executor, dropped));
    }

    @Test
    void httpException500_staysJenaTDB2Exception() {
        HttpException serverError = new HttpException(500, "Internal Server Error", "boom");
        FusekiSparqlExecutor executor = executorThrowing(serverError);

        JenaTDB2Exception ex = assertThrows(JenaTDB2Exception.class,
                () -> run(executor, serverError));
        assertEquals(USER_MSG, ex.getMessage());
        assertSame(serverError, ex.getCause());
    }

    @Test
    void queryExceptionHttp404_mapsToOntologyNotFound() {
        QueryExceptionHTTP notFound = new QueryExceptionHTTP(404, "Not Found");
        FusekiSparqlExecutor executor = executorThrowing(notFound);

        assertThrows(OntologyNotFoundException.class, () -> run(executor, notFound));
    }
}
