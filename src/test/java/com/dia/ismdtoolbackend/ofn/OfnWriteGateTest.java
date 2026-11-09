package com.dia.ismdtoolbackend.ofn;

import com.dia.ismdtoolbackend.exception.OfnConformanceException;
import com.dia.ismdtoolbackend.ofn.ConformanceIssue.Kind;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfnWriteGateTest {

    private static final String V = "https://slovník.gov.cz/test";
    private static final String NS = V + "/pojem/";

    private final OfnSpecification specification = OfnSpecification.bundled();
    private final OfnWriteGate gate = new OfnWriteGate(
            new OfnRdfReader(specification),
            new OfnConformanceGate(specification));

    // ------------------------------------------------------------ T3 never blocks a write

    @Test
    void draftVztahWithoutDomainAndRange_isWritable_butIncomplete() {
        Model model = vocabularyModel();
        concept(model, "a", OWL2.ObjectProperty);

        assertThatCode(() -> gate.requireWritableConcepts(model, V, List.of(NS + "a"))).doesNotThrowAnyException();
        assertThatCode(() -> gate.requireWritableVocabulary(model, V)).doesNotThrowAnyException();
        List<ConformanceIssue> issues = gate.checkVocabulary(model, V);
        assertThat(issues).extracting(ConformanceIssue::kind).containsOnly(Kind.CONCEPT_INCOMPLETE);
        assertThat(issues.get(0).message()).contains("definiční-obor").contains("obor-hodnot");
    }

    @Test
    void completeVocabulary_hasNoIssues() {
        Model model = vocabularyModel();
        Resource b = concept(model, "b", OWL2.Class);
        concept(model, "a", OWL2.ObjectProperty)
                .addProperty(RDFS.domain, b)
                .addProperty(RDFS.range, b);

        OfnWriteGate.Inspection inspection = gate.inspectVocabulary(model, V);
        assertThat(inspection.issues()).isEmpty();
        assertThat(inspection.document().get("pojmy")).hasSize(2);
    }

    // ------------------------------------------------------------ T1 / T2 block

    @Test
    void shapeIssue_blocksTheWrite() {
        Model model = vocabularyModel();
        concept(model, "a", OWL2.Class)
                .addProperty(model.createProperty(OfnIris.JE_SDILEN_V_PPDF), "false");

        assertThatThrownBy(() -> gate.requireWritableConcepts(model, V, List.of(NS + "a")))
                .isInstanceOfSatisfying(OfnConformanceException.class,
                        e -> assertThat(e.getContext()).isEqualTo(OfnConformanceException.Context.WRITE));
    }

    @Test
    void roleMarker_isNotOfn_andBlocksTheWrite() {
        Model model = vocabularyModel();
        concept(model, "a", OWL2.Class).addProperty(RDF.type, model.createResource(OfnIris.SLOVNIKY + "třída"));

        assertThat(WritableIssues.of(gate.checkConcepts(model, V, List.of(NS + "a"))))
                .singleElement()
                .satisfies(issue -> assertThat(issue.message()).contains("není definován v kontextu OFN"));
    }

    @Test
    void conceptWithoutOwlKind_blocksTheWrite() {
        Model model = vocabularyModel();
        concept(model, "a", null);

        assertThat(WritableIssues.of(gate.checkConcepts(model, V, List.of(NS + "a"))))
                .anySatisfy(issue -> assertThat(issue.message()).contains("nemá typ Třída, Vlastnost ani Vztah"));
    }

    @Test
    void twoOwlKinds_blockTheWrite() {
        Model model = vocabularyModel();
        concept(model, "a", OWL2.DatatypeProperty).addProperty(RDF.type, OWL2.ObjectProperty);

        assertThat(WritableIssues.of(gate.checkConcepts(model, V, List.of(NS + "a"))))
                .anySatisfy(issue -> assertThat(issue.message()).contains("více typů"));
    }

    @Test
    void conceptCheck_ignoresProblemsElsewhereInTheVocabulary() {
        Model model = vocabularyModel();
        concept(model, "a", OWL2.Class);
        concept(model, "broken", null).addProperty(model.createProperty(V + "/x"), "y");

        assertThat(gate.checkConcepts(model, V, List.of(NS + "a"))).isEmpty();
        assertThat(WritableIssues.of(gate.checkVocabulary(model, V)))
                .isNotEmpty()
                .allSatisfy(issue -> assertThat(issue.conceptIri()).isEqualTo(NS + "broken"));
    }

    // ------------------------------------------------------------ fixtures

    /** The issues that would block a write. */
    private static final class WritableIssues {
        static List<ConformanceIssue> of(List<ConformanceIssue> issues) {
            return OfnWriteGate.blocking(issues);
        }
    }

    private Model vocabularyModel() {
        Model model = ModelFactory.createDefaultModel();
        model.createResource(V)
                .addProperty(RDF.type, model.createResource(OfnIris.SLOVNIK))
                .addProperty(RDF.type, model.createResource(OfnIris.CONCEPT_SCHEME))
                .addProperty(RDF.type, model.createResource(OfnIris.ONTOLOGY))
                .addProperty(SKOS.prefLabel, "Test", "cs");
        return model;
    }

    private Resource concept(Model model, String name, Resource kind) {
        Resource concept = model.createResource(NS + name)
                .addProperty(RDF.type, SKOS.Concept)
                .addProperty(RDF.type, model.createResource(OfnIris.POJEM))
                .addProperty(SKOS.prefLabel, name, "cs")
                .addProperty(SKOS.inScheme, model.createResource(V));
        if (kind != null) {
            concept.addProperty(RDF.type, kind);
        }
        return concept;
    }
}
