package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.models.OntologyEditModel;
import com.dia.ismdtoolbackend.testsupport.fixtures.Texts;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ontology edits through {@link OntologyEditor}: name, description and the rename of the vocabulary IRI. */
class OntologyEditorTest {

    private static final String OLD_IRI = "https://example.com/vocab/old-ontology";
    private static final String OLD_NS = OLD_IRI + "/";
    private static final String NEW_IRI = "https://example.com/new-ontology";
    private static final String NEW_NS = NEW_IRI + "/";

    private OntologyEditor ontologyEditor;
    private Model model;

    @BeforeEach
    void setUp() {
        ontologyEditor = new OntologyEditor();
        model = ModelFactory.createDefaultModel();
    }

    private static OntologyEditModel edit(String newCsName, Map<String, String> description) {
        OntologyEditModel editModel = new OntologyEditModel();
        editModel.setNameModel(newCsName == null ? null : Texts.name("cs", newCsName));
        editModel.setDescriptionModel(description == null ? null : Texts.description(description));
        return editModel;
    }

    private static Map<String, String> cs(String value) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("cs", value);
        return map;
    }

    private void assertModelIs(String turtle) {
        Model expected = Ttl.parse(turtle);
        assertThat(model.isIsomorphicWith(expected))
                .as("missing: %s%nextra: %s", expected.difference(model), model.difference(expected))
                .isTrue();
    }

    @Nested
    class Rename {

        @Test
        void newNameMovesTheVocabularyAndEveryConceptInItsNamespace() {
            Ttl.add(model, """
                    <%1$s> skos:prefLabel "Old name"@cs ; dct:description "Old description"@cs ;
                        <http://example.com/hasConcept> <%2$sconcept-1> .
                    <%2$sconcept-1> a skos:Concept ; skos:prefLabel "Concept 1" .
                    <%2$sconcept-2> a skos:Concept ; skos:prefLabel "Concept 2" .
                    <%2$sother-subject> <http://example.com/refersTo> <%2$sconcept-1> .
                    """.formatted(OLD_IRI, OLD_NS));

            OntologyEditor.EditResult result = ontologyEditor.editOntology(
                    edit("New ontology", cs("New description")), model, OLD_NS, OLD_IRI);

            assertThat(result.iriChanged).isTrue();
            assertThat(result.newOntologyIRI).isEqualTo(NEW_IRI);
            assertModelIs("""
                    <%1$s> skos:prefLabel "New ontology"@cs ; dct:description "New description"@cs ;
                        <http://example.com/hasConcept> <%2$sconcept-1> .
                    <%2$sconcept-1> a skos:Concept ; skos:prefLabel "Concept 1" .
                    <%2$sconcept-2> a skos:Concept ; skos:prefLabel "Concept 2" .
                    <%3$sother-subject> <http://example.com/refersTo> <%2$sconcept-1> .
                    """.formatted(NEW_IRI, NEW_NS, OLD_NS));
        }

        @Test
        void conceptsPointTheirSchemeAtTheNewVocabulary() {
            Ttl.add(model, """
                    <%1$s> skos:prefLabel "Old name"@cs .
                    <%2$sconcept-1> a skos:Concept ; skos:inScheme <%1$s> .
                    """.formatted(OLD_IRI, OLD_NS));

            ontologyEditor.editOntology(edit("New ontology", null), model, OLD_NS, OLD_IRI);

            assertModelIs("""
                    <%1$s> skos:prefLabel "New ontology"@cs .
                    <%2$sconcept-1> a skos:Concept ; skos:inScheme <%1$s> .
                    """.formatted(NEW_IRI, NEW_NS));
        }

        @Test
        void linksBetweenTwoRenamedConceptsPointAtTheNewIris() {
            Ttl.add(model, """
                    <%1$s> skos:prefLabel "Old name"@cs .
                    <%2$spojem/a> a skos:Concept ; rdfs:subClassOf <%2$spojem/b> .
                    <%2$spojem/b> a skos:Concept .
                    """.formatted(OLD_IRI, OLD_NS));

            ontologyEditor.editOntology(edit("New ontology", null), model, OLD_NS, OLD_IRI);

            assertModelIs("""
                    <%1$s> skos:prefLabel "New ontology"@cs .
                    <%2$spojem/a> a skos:Concept ; rdfs:subClassOf <%2$spojem/b> .
                    <%2$spojem/b> a skos:Concept .
                    """.formatted(NEW_IRI, NEW_NS));
        }

        @Test
        void editedNameAndDescriptionReplaceTheCopiedValues() {
            Ttl.add(model, """
                    <%s> skos:prefLabel "Old name"@cs ; dct:description "Old description"@cs .
                    """.formatted(OLD_IRI));

            ontologyEditor.editOntology(edit("New ontology", cs("New description")), model, OLD_NS, OLD_IRI);

            assertModelIs("""
                    <%s> skos:prefLabel "New ontology"@cs ; dct:description "New description"@cs .
                    """.formatted(NEW_IRI));
        }

        @Test
        void uneditedDescriptionMovesWithTheRename() {
            Ttl.add(model, """
                    <%s> skos:prefLabel "Old name"@cs ; dct:description "Kept description"@cs .
                    """.formatted(OLD_IRI));

            ontologyEditor.editOntology(edit("New ontology", null), model, OLD_NS, OLD_IRI);

            assertModelIs("""
                    <%s> skos:prefLabel "New ontology"@cs ; dct:description "Kept description"@cs .
                    """.formatted(NEW_IRI));
        }

        @Test
        void renameAndClearedDescriptionLeaveNoDescriptionOnTheNewIri() {
            Ttl.add(model, """
                    <%s> skos:prefLabel "Old name"@cs ; dct:description "Old description"@cs .
                    """.formatted(OLD_IRI));

            ontologyEditor.editOntology(edit("New ontology", Map.of()), model, OLD_NS, OLD_IRI);

            assertModelIs("<%s> skos:prefLabel \"New ontology\"@cs .".formatted(NEW_IRI));
        }

        @Test
        void unchangedNameKeepsEveryIri() {
            String iri = "https://example.com/vocab/ontology";
            Ttl.add(model, """
                    <%1$s> skos:prefLabel "Ontology"@cs ; <http://example.com/hasConcept> <%1$s/concept-1> .
                    <%1$s/concept-1> a skos:Concept .
                    """.formatted(iri));
            Model before = ModelFactory.createDefaultModel().add(model);

            OntologyEditor.EditResult result = ontologyEditor.editOntology(
                    edit("Ontology", null), model, iri + "/", iri);

            assertThat(result.iriChanged).isFalse();
            assertThat(result.newOntologyIRI).isEqualTo(iri);
            assertThat(model.isIsomorphicWith(before)).isTrue();
        }

        @Test
        void renameOntoAnIriAlreadyInTheModelIsRejectedWithoutAWrite() {
            Ttl.add(model, """
                    <%s> skos:prefLabel "Old name"@cs .
                    <%s> skos:prefLabel "Existing ontology"@cs .
                    """.formatted(OLD_IRI, NEW_IRI));
            Model before = ModelFactory.createDefaultModel().add(model);

            assertThatThrownBy(() -> ontologyEditor.editOntology(edit("New ontology", null), model, OLD_NS, OLD_IRI))
                    .isExactlyInstanceOf(OntologyValidationException.class)
                    .hasMessage("Ontology with IRI " + NEW_IRI + " already exists in the model");
            assertThat(model.isIsomorphicWith(before)).isTrue();
        }
    }

    @Nested
    class Labels {

        private static final String IRI = "https://example.com/vocab/ontology";

        @Test
        void blankDescriptionRemovesIt() {
            Ttl.add(model, "<%s> skos:prefLabel \"Slovník\"@cs ; dct:description \"Popis\"@cs .".formatted(IRI));

            OntologyEditor.EditResult result = ontologyEditor.editOntology(edit(null, cs("")), model, IRI + "/", IRI);

            assertThat(result.iriChanged).isFalse();
            assertModelIs("<%s> skos:prefLabel \"Slovník\"@cs .".formatted(IRI));
        }

        @Test
        void newDescriptionReplacesTheOldOne() {
            Ttl.add(model, "<%s> skos:prefLabel \"Slovník\"@cs ; dct:description \"Old\"@cs .".formatted(IRI));

            ontologyEditor.editOntology(edit(null, cs("Updated")), model, IRI + "/", IRI);

            assertModelIs("<%s> skos:prefLabel \"Slovník\"@cs ; dct:description \"Updated\"@cs .".formatted(IRI));
        }

        @Test
        void descriptionEditMergesPerLanguage() {
            Ttl.add(model, """
                    <%s> skos:prefLabel "Slovník"@cs ; dct:description "Popis"@cs, "Description"@en .
                    """.formatted(IRI));
            Map<String, String> description = new LinkedHashMap<>();
            description.put("en", "New description");
            description.put("de", " ");

            ontologyEditor.editOntology(edit(null, description), model, IRI + "/", IRI);

            assertModelIs("""
                    <%s> skos:prefLabel "Slovník"@cs ; dct:description "Popis"@cs, "New description"@en .
                    """.formatted(IRI));
        }

        @Test
        void emptyDescriptionMapOnAnUndescribedVocabularyChangesNothing() {
            Ttl.add(model, "<%s> skos:prefLabel \"Slovník\"@cs .".formatted(IRI));

            ontologyEditor.editOntology(edit(null, Map.of()), model, IRI + "/", IRI);

            assertModelIs("<%s> skos:prefLabel \"Slovník\"@cs .".formatted(IRI));
        }

        @Test
        void englishNameIsAddedNextToTheCzechOneWithoutARename() {
            Ttl.add(model, "<%s> skos:prefLabel \"Slovník\"@cs .".formatted(IRI));
            OntologyEditModel editModel = new OntologyEditModel();
            editModel.setNameModel(Texts.name(Map.of("cs", "Slovník", "en", "Vocabulary")));

            OntologyEditor.EditResult result = ontologyEditor.editOntology(editModel, model, IRI + "/", IRI);

            assertThat(result.iriChanged).isFalse();
            assertModelIs("<%s> skos:prefLabel \"Slovník\"@cs, \"Vocabulary\"@en .".formatted(IRI));
        }

        @Test
        void nameWithoutCzechFallsBackToAnotherLanguageForTheIri() {
            Ttl.add(model, "<%s> skos:prefLabel \"Vocabulary\"@en .".formatted(OLD_IRI));
            OntologyEditModel editModel = new OntologyEditModel();
            editModel.setNameModel(Texts.name("en", "New ontology"));

            OntologyEditor.EditResult result = ontologyEditor.editOntology(editModel, model, OLD_NS, OLD_IRI);

            assertThat(result.newOntologyIRI).isEqualTo(NEW_IRI);
            assertModelIs("<%s> skos:prefLabel \"New ontology\"@en .".formatted(NEW_IRI));
        }

        @Test
        void editWithNeitherNameNorDescriptionChangesNothing() {
            Ttl.add(model, "<%s> skos:prefLabel \"Slovník\"@cs .".formatted(IRI));

            OntologyEditor.EditResult result = ontologyEditor.editOntology(new OntologyEditModel(), model, IRI + "/", IRI);

            assertThat(result.iriChanged).isFalse();
            assertModelIs("<%s> skos:prefLabel \"Slovník\"@cs .".formatted(IRI));
        }
    }

    @Nested
    class Failures {

        @Test
        void vocabularyMissingFromTheModelIsRejected() {
            assertThatThrownBy(() -> ontologyEditor.editOntology(edit("X", null), model, OLD_NS, OLD_IRI))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Ontology with IRI " + OLD_IRI + " not found in the model");
        }

        @Test
        void transactionalModelIsCommittedOnSuccess() {
            Dataset dataset = DatasetFactory.createTxnMem();
            dataset.executeWrite(() -> Ttl.add(dataset.getDefaultModel(),
                    "<%s> skos:prefLabel \"Old name\"@cs .".formatted(OLD_IRI)));
            Model txnModel = dataset.getDefaultModel();

            ontologyEditor.editOntology(edit("New ontology", null), txnModel, OLD_NS, OLD_IRI);

            assertThat(dataset.isInTransaction()).isFalse();
            assertThat(dataset.calculateRead(() -> txnModel.isIsomorphicWith(
                    Ttl.parse("<%s> skos:prefLabel \"New ontology\"@cs .".formatted(NEW_IRI))))).isTrue();
        }

        @Test
        void failureWhileStagingIsWrappedAndRolledBack() {
            Dataset dataset = DatasetFactory.createTxnMem();
            dataset.executeWrite(() -> Ttl.add(dataset.getDefaultModel(),
                    "<%s> skos:prefLabel \"Slovník\"@cs .".formatted(OLD_IRI)));
            Model txnModel = dataset.getDefaultModel();
            Model before = dataset.calculateRead(() -> ModelFactory.createDefaultModel().add(txnModel));
            OntologyEditModel failing = new OntologyEditModel() {
                @Override
                public com.dia.ismdtoolbackend.models.DescriptionModel getDescriptionModel() {
                    throw new IllegalStateException("boom");
                }
            };

            assertThatThrownBy(() -> ontologyEditor.editOntology(failing, txnModel, OLD_NS, OLD_IRI))
                    .isExactlyInstanceOf(OntologyException.class)
                    .hasMessage("Failed to edit ontology: " + OLD_IRI + " - boom")
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(dataset.isInTransaction()).isFalse();
            assertThat(dataset.calculateRead(() -> txnModel.isIsomorphicWith(before))).isTrue();
        }
    }
}
