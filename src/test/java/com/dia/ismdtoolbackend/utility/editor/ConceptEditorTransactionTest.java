package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.testsupport.fixtures.EditModels;
import com.dia.ismdtoolbackend.testsupport.fixtures.Texts;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Test;

import static com.dia.constants.VocabularyConstants.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Failure handling of {@link ConceptEditor#editConcept}: cause propagation, atomicity and transactions. */
class ConceptEditorTransactionTest extends ConceptEditorTestBase {

    private static final String CONCEPT_IRI = DEFAULT_NS + "pojem/třída-tx";

    /** A real edit model whose {@code type} read fails, which happens after the common fields are staged. */
    private static ClassConceptEditModel failingOnType(RuntimeException failure) {
        ClassConceptEditModel model = new ClassConceptEditModel() {
            @Override
            public String getType() {
                throw failure;
            }
        };
        model.setConceptType("TRIDA");
        return model;
    }

    private static void seedClass(Model target, String label) {
        Resource concept = target.createResource(CONCEPT_IRI);
        concept.addProperty(SKOS.prefLabel, target.createLiteral(label, "cs"));
        concept.addProperty(RDF.type, target.getResource(OFN_NAMESPACE + TRIDA));
    }

    private static Model copyOf(Model source) {
        return ModelFactory.createDefaultModel().add(source);
    }

    @Test
    void failureWhileStagingIsWrappedWithTheConceptIriAndOriginalCause() {
        seedClass(model, "Třída");
        RuntimeException cause = new RuntimeException("Simulated failure");

        assertThatThrownBy(() -> conceptEditor.editConcept(CONCEPT_IRI, failingOnType(cause), model, null))
                .isExactlyInstanceOf(OntologyException.class)
                .hasMessage("Failed to edit concept: " + CONCEPT_IRI + " - Simulated failure")
                .hasCause(cause);
    }

    @Test
    void failureAfterARenameWasStagedLeavesTheModelUntouched() {
        seedClass(model, "Třída");
        model.getResource(CONCEPT_IRI).addProperty(model.createProperty(DEFAULT_NS + "custom-prop"), "custom-value");
        Model before = copyOf(model);
        ClassConceptEditModel edit = failingOnType(new IllegalStateException("boom"));
        edit.setNameModel(Texts.name("cs", "Nový název"));
        edit.setDescriptionModel(Texts.description("cs", "Nový popis"));

        assertThatThrownBy(() -> conceptEditor.editConcept(CONCEPT_IRI, edit, model, null))
                .isExactlyInstanceOf(OntologyException.class);

        assertThat(model.isIsomorphicWith(before)).isTrue();
    }

    @Test
    void transactionalModelIsRolledBackOnFailure() {
        Dataset dataset = DatasetFactory.createTxnMem();
        dataset.executeWrite(() -> seedClass(dataset.getDefaultModel(), "Třída"));
        Model txnModel = dataset.getDefaultModel();
        Model before = dataset.calculateRead(() -> copyOf(txnModel));
        ClassConceptEditModel edit = failingOnType(new IllegalStateException("boom"));
        edit.setDescriptionModel(Texts.description("cs", "Nový popis"));

        assertThatThrownBy(() -> conceptEditor.editConcept(CONCEPT_IRI, edit, txnModel, null))
                .isExactlyInstanceOf(OntologyException.class)
                .hasRootCauseMessage("boom");

        assertThat(dataset.isInTransaction()).isFalse();
        assertThat(dataset.calculateRead(() -> txnModel.isIsomorphicWith(before))).isTrue();
    }

    @Test
    void transactionalModelIsCommittedOnSuccess() {
        Dataset dataset = DatasetFactory.createTxnMem();
        dataset.executeWrite(() -> seedClass(dataset.getDefaultModel(), "Třída"));
        Model txnModel = dataset.getDefaultModel();

        conceptEditor.editConcept(CONCEPT_IRI,
                EditModels.trida(m -> m.setDescriptionModel(Texts.description("cs", "Nový popis"))),
                txnModel, null);

        assertThat(dataset.isInTransaction()).isFalse();
        dataset.begin(ReadWrite.READ);
        try {
            assertThat(txnModel.contains(txnModel.getResource(CONCEPT_IRI),
                    txnModel.createProperty(DCT_NS + "description"),
                    txnModel.createLiteral("Nový popis", "cs"))).isTrue();
        } finally {
            dataset.end();
        }
    }

    @Test
    void renameReturnsTheIriDerivedFromTheNewName() {
        seedClass(model, "Starý název");

        ConceptEditor.EditResult result = conceptEditor.editConcept(CONCEPT_IRI,
                EditModels.trida(m -> m.setNameModel(Texts.name("cs", "Zcela nový název"))), model, null);

        assertThat(result.iriChanged).isTrue();
        assertThat(result.newConceptIRI).isEqualTo(DEFAULT_NS + "pojem/zcela-nový-název");
        assertThat(model.contains(model.getResource(result.newConceptIRI), SKOS.prefLabel,
                model.createLiteral("Zcela nový název", "cs"))).isTrue();
        assertThat(model.containsResource(model.getResource(CONCEPT_IRI))).isFalse();
    }
}
