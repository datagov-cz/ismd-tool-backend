package com.dia.ismdtoolbackend.outbox;

import com.dia.ismdtoolbackend.exception.JenaTDB2Exception;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdfconnection.RDFConnection;
import org.springframework.mock.env.MockEnvironment;

import java.net.http.HttpClient;
import java.util.concurrent.Semaphore;

/**
 * A {@link JenaTDB2Repository} backed by a REAL in-memory Jena {@link Dataset} instead of remote
 * Fuseki, so the relay's apply path ({@code applyConceptDelta}/{@code deleteConceptsFromGraph}/
 * {@code deleteGraph}) runs with true RDF semantics in tests. {@code createConnection()} returns a
 * fresh connection view over the shared dataset each call (the executor closes it per call; closing
 * a local connection view does not destroy the dataset).
 *
 * <p>Fault injection: {@link #failApplyForGraph(String)} makes writes to one graph throw
 * {@link JenaTDB2Exception}, to exercise the relay's retry/FAILED path.
 */
public class InMemoryTdb2 extends JenaTDB2Repository {

    private Dataset dataset = DatasetFactory.createTxnMem();
    private String failingGraph;

    public InMemoryTdb2() {
        super(HttpClient.newHttpClient(), new Semaphore(4), 5000, new MockEnvironment());
    }

    @Override
    public void init() {
        // No-op: skip the parent's Fuseki connection probe + text-index check (the @PostConstruct),
        // which would throw with no real Fuseki. The in-memory dataset needs no probing.
    }

    @Override
    protected RDFConnection createConnection() {
        return RDFConnection.connect(dataset);
    }

    public Dataset dataset() {
        return dataset;
    }

    public void reset() {
        dataset = DatasetFactory.createTxnMem();
        failingGraph = null;
    }

    public void failApplyForGraph(String graphName) {
        this.failingGraph = graphName;
    }

    @Override
    public void applyConceptDelta(String graphName, Model removeModel, Model addModel) {
        throwIfFailing(graphName);
        super.applyConceptDelta(graphName, removeModel, addModel);
    }

    @Override
    public void deleteGraph(String graphName) {
        throwIfFailing(graphName);
        super.deleteGraph(graphName);
    }

    private void throwIfFailing(String graphName) {
        if (failingGraph != null && failingGraph.equals(graphName)) {
            throw new JenaTDB2Exception("Injected failure for graph " + graphName);
        }
    }
}
