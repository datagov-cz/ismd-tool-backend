package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** One shared {@link ConceptCreator} must build concurrent concepts independently of each other. */
class ConceptCreatorConcurrencyTest {

    private static final int THREADS = 8;
    private static final int ROUNDS_PER_THREAD = 150;

    private final ConceptCreator conceptCreator = new ConceptCreator();

    @Test
    void concurrentCreates_eachGetItsOwnNamespaceAndStatements() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int thread = 0; thread < THREADS; thread++) {
                futures.add(pool.submit(creates(thread, start)));
            }
            start.countDown();

            List<String> mismatches = new ArrayList<>();
            for (Future<List<String>> future : futures) {
                mismatches.addAll(future.get(120, TimeUnit.SECONDS));
            }
            assertThat(mismatches).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<List<String>> creates(int thread, CountDownLatch start) {
        return () -> {
            String graph = "https://example.test/slovnik-" + thread;
            List<String> mismatches = new ArrayList<>();
            start.await();
            for (int round = 0; round < ROUNDS_PER_THREAD; round++) {
                String name = "pojem " + thread + " " + round;
                Resource created = conceptCreator.createSingleConcept(classModel(graph, name));
                String expectedIriPrefix = graph + "/";
                if (!created.getURI().startsWith(expectedIriPrefix)) {
                    mismatches.add(created.getURI() + " is outside " + expectedIriPrefix);
                }
                List<String> labels = created.listProperties(org.apache.jena.vocabulary.SKOS.prefLabel)
                        .mapWith(statement -> statement.getString()).toList();
                if (!labels.equals(List.of(name))) {
                    mismatches.add(created.getURI() + " carries labels " + labels + ", expected " + name);
                }
            }
            return mismatches;
        };
    }

    private static ClassConceptModel classModel(String graph, String name) {
        ClassConceptModel model = new ClassConceptModel();
        model.setConceptType("třída");
        model.setType("objekt");
        model.setOntologyGraphName(graph);
        model.setNamespace(graph);
        NameModel nameModel = new NameModel();
        nameModel.setName(Map.of("cs", name));
        model.setNameModel(nameModel);
        return model;
    }
}
