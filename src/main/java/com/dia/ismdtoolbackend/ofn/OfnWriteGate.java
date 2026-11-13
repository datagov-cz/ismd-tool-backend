package com.dia.ismdtoolbackend.ofn;

import com.dia.ismdtoolbackend.exception.OfnConformanceException;
import com.dia.ismdtoolbackend.exception.OfnConformanceException.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.jena.rdf.model.Model;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * The OFN check of ISMD data: the RDF as it is (or will be) stored is read into an OFN document
 * ({@link OfnRdfReader}), gated against the OFN context and schema ({@link OfnConformanceGate}) and
 * checked against ISMD's concept-kind profile ({@link ConceptKindRule}). Nothing is repaired.
 * <p>
 * Issues fall into three cumulative tiers: T1 shape and T2 concept kind must always hold in storage;
 * T3 completeness ({@link ConformanceIssue.Kind#isCompleteness()}) never blocks a write, because a
 * concept may be a draft (e.g. a Vztah drawn in the diagram without its range yet), but blocks the
 * download ({@link OfnVocabularyExporter}).
 */
@Component
public class OfnWriteGate {

    private final OfnRdfReader reader;
    private final OfnConformanceGate gate;

    public OfnWriteGate(OfnRdfReader reader, OfnConformanceGate gate) {
        this.reader = reader;
        this.gate = gate;
    }

    /** Every issue of the given concepts (all tiers), as they are in the graph. */
    public List<ConformanceIssue> checkConcepts(Model graph, String vocabularyIri, Collection<String> conceptIris) {
        Set<String> scope = new HashSet<>(conceptIris);
        OfnRdfReader.Result read = reader.readConcepts(graph, vocabularyIri, scope);
        GateResult gated = gate.apply(read.document());

        List<ConformanceIssue> issues = new ArrayList<>(read.issues());
        issues.addAll(gated.issues());
        issues.addAll(ConceptKindRule.check(graph, new TreeSet<>(scope)));
        return issues.stream().filter(issue -> scope.contains(issue.conceptIri())).toList();
    }

    /** The vocabulary read as an OFN document, after the gate, with every issue (all tiers). */
    public record Inspection(ObjectNode document, List<ConformanceIssue> issues) {
        public Inspection {
            issues = List.copyOf(issues);
        }
    }

    /** The vocabulary and all its concepts: the gated document and every issue (all tiers). */
    public Inspection inspectVocabulary(Model graph, String vocabularyIri) {
        OfnRdfReader.Result read = reader.readVocabulary(graph, vocabularyIri);
        GateResult gated = gate.apply(read.document());

        List<ConformanceIssue> issues = new ArrayList<>(read.issues());
        issues.addAll(gated.issues());
        issues.addAll(ConceptKindRule.check(graph, conceptIris(read.document())));
        return new Inspection(gated.document(), issues);
    }

    /** Every issue of the vocabulary and all its concepts (all tiers). */
    public List<ConformanceIssue> checkVocabulary(Model graph, String vocabularyIri) {
        return inspectVocabulary(graph, vocabularyIri).issues();
    }

    /** Safety net of form and diagram concept writes: refuses a write that breaks T1 or T2. */
    public void requireWritableConcepts(Model graph, String vocabularyIri, Collection<String> conceptIris) {
        List<ConformanceIssue> blocking = blocking(checkConcepts(graph, vocabularyIri, conceptIris));
        if (!blocking.isEmpty()) {
            throw new OfnConformanceException(Context.WRITE, vocabularyIri, blocking);
        }
    }

    /** Safety net of vocabulary writes: refuses a write that breaks T1 or T2 anywhere in the vocabulary. */
    public void requireWritableVocabulary(Model graph, String vocabularyIri) {
        List<ConformanceIssue> blocking = blocking(checkVocabulary(graph, vocabularyIri));
        if (!blocking.isEmpty()) {
            throw new OfnConformanceException(Context.WRITE, vocabularyIri, blocking);
        }
    }

    /**
     * The issues that block a write: T1 and T2. Not completeness, and not a removed reference, which
     * only follows from the referenced concept's own (already blocking) failure.
     */
    public static List<ConformanceIssue> blocking(List<ConformanceIssue> issues) {
        return issues.stream()
                .filter(issue -> !issue.kind().isCompleteness() && issue.kind() != ConformanceIssue.Kind.REFERENCE_REMOVED)
                .toList();
    }

    private static List<String> conceptIris(JsonNode document) {
        List<String> iris = new ArrayList<>();
        document.path(OfnSpecification.CONCEPTS_KEY).forEach(concept -> iris.add(concept.path(OfnTerms.IRI).asText()));
        return iris;
    }
}
