package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.testsupport.fixtures.Texts;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.testsupport.rdf.Ttl.literals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Rejections and namespace resolution of {@link ConceptEditor#editConcept}. */
class ConceptEditorErrorHandlingTest extends ConceptEditorTestBase {

    private static final String OLD_IRI = "https://slovnik.gov.cz/pojem/old-class";
    private static final String RENAMED_IRI = DEFAULT_NS + "pojem/id-1";

    private void seedOldClass() {
        Ttl.add(model, "<" + OLD_IRI + "> a ofn:třída ; skos:prefLabel \"Old name\"@cs .");
        classConceptEditModel.setNameModel(Texts.name("cs", "New name"));
        classConceptEditModel.setIdentifier("ID-1");
    }

    @Test
    void conceptMissingFromTheModelIsRejected() {
        String missingIri = DEFAULT_NS + "missing-concept";

        assertThatThrownBy(() -> conceptEditor.editConcept(missingIri, classConceptEditModel, model, null))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Concept with IRI " + missingIri + " not found in the model");
    }

    @Test
    void renameOntoAnIriClaimedByAnotherOwnedConceptIsRejectedWithoutAWrite() {
        seedOldClass();
        Model before = ModelFactory.createDefaultModel().add(model);

        assertThatThrownBy(() -> conceptEditor.editConcept(OLD_IRI, classConceptEditModel, model, null,
                RENAMED_IRI::equals))
                .isExactlyInstanceOf(ConceptValidationException.class)
                .hasMessage("Pojem se stejným názvem již v ontologii existuje: " + RENAMED_IRI);
        assertThat(model.isIsomorphicWith(before)).isTrue();
    }

    @Test
    void renameOntoAnIriThatIsOnlyReferencedInTheGraphIsAllowed() {
        seedOldClass();
        Ttl.add(model, "<" + RENAMED_IRI + "> a skos:Concept .");

        ConceptEditor.EditResult result = conceptEditor.editConcept(
                OLD_IRI, classConceptEditModel, model, null, iri -> false);

        assertThat(result.newConceptIRI).isEqualTo(RENAMED_IRI);
    }

    @Test
    void validGraphNameIsTheNamespaceOfVocabularyPredicates() {
        String conceptIri = DEFAULT_NS + "pojem/třída";
        Ttl.add(model, "<" + conceptIri + "> a ofn:třída ; skos:prefLabel \"Třída\"@cs .");
        classConceptEditModel.setInTezaurus(Boolean.TRUE);

        conceptEditor.editConcept(conceptIri, classConceptEditModel, model, "https://custom.cz/vocab/");

        assertThat(literals(model, conceptIri, Ttl.property("https://custom.cz/vocab/inTezaurus")))
                .containsExactly("true");
        assertThat(model.getResource(conceptIri).hasProperty(Ttl.property(DEFAULT_NS + "inTezaurus"))).isFalse();
    }

    @Test
    void invalidGraphNameFallsBackToTheDefaultNamespace() {
        String conceptIri = DEFAULT_NS + "pojem/třída";
        Ttl.add(model, "<" + conceptIri + "> a ofn:třída ; skos:prefLabel \"Třída\"@cs .");
        classConceptEditModel.setInTezaurus(Boolean.FALSE);

        conceptEditor.editConcept(conceptIri, classConceptEditModel, model, "not an iri");

        assertThat(literals(model, conceptIri, Ttl.property(DEFAULT_NS + "inTezaurus"))).containsExactly("false");
    }
}
