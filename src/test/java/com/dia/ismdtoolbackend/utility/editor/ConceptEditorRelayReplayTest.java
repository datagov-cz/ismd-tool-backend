package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.concept.DigitalObjectModel;
import com.dia.ismdtoolbackend.outbox.InMemoryTdb2;
import com.dia.ismdtoolbackend.testsupport.fixtures.Texts;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The editor's change set, replayed by the relay's {@code applyConceptDelta} against TDB2, must leave
 * TDB2 equal to the edited model. The relay deletes all of a concept's blank children when a delta
 * touches one, and keys a rename on the old IRI, so unchanged sources must ride the change set too.
 */
class ConceptEditorRelayReplayTest extends ConceptEditorTestBase {

    private static final String GRAPH = "https://slovník.gov.cz/datový/test-slovník";
    private static final String OLD = GRAPH + "/pojem/třída";
    private static final String SOURCES = """
            <%s> a ofn:třída, owl:Class ; skos:prefLabel "Třída"@cs ; skos:inScheme <%s> ;
                slovníky:definující-nelegislativní-zdroj [ a do:digitální-objekt ;
                    schema:url "https://example.com/d1"^^xsd:anyURI ; dct:title "D1"@cs ] ;
                slovníky:související-nelegislativní-zdroj [ a do:digitální-objekt ;
                    schema:url "https://example.com/r1"^^xsd:anyURI ; dct:title "R1"@cs ] .
            """.formatted(OLD, GRAPH);

    private final InMemoryTdb2 tdb2 = new InMemoryTdb2();

    @BeforeEach
    void seed() {
        Ttl.add(model, SOURCES);
        tdb2.dataset().getNamedModel(GRAPH).add(model);
    }

    private ConceptEditor.EditResult editAndReplay() {
        ConceptEditor.EditResult result = conceptEditor.editConcept(OLD, classConceptEditModel, model, GRAPH);
        Model remove = ModelFactory.createDefaultModel().add(List.copyOf(result.statementsToRemove));
        Model add = ModelFactory.createDefaultModel().add(List.copyOf(result.statementsToAdd));
        tdb2.applyConceptDelta(OLD, GRAPH, remove, add);
        return result;
    }

    private void assertTdb2EqualsTheEditedModel() {
        Model stored = ModelFactory.createDefaultModel().add(tdb2.dataset().getNamedModel(GRAPH));
        assertThat(stored.isIsomorphicWith(model))
                .as("missing in TDB2: %s%nextra in TDB2: %s", model.difference(stored), stored.difference(model))
                .isTrue();
    }

    @Test
    void editingOneSourceListKeepsTheOther() {
        classConceptEditModel.setDefiningNonLegalSource(List.of(new DigitalObjectModel("D2", null, "https://example.com/d2")));
        classConceptEditModel.setRelatedNonLegalSource(List.of(new DigitalObjectModel("R1", null, "https://example.com/r1")));

        editAndReplay();

        assertTdb2EqualsTheEditedModel();
    }

    @Test
    void renameWithUnchangedSourcesKeepsTheirContents() {
        classConceptEditModel.setNameModel(Texts.name("cs", "Nová třída"));

        ConceptEditor.EditResult result = editAndReplay();

        assertThat(result.iriChanged).isTrue();
        assertTdb2EqualsTheEditedModel();
    }

    @Test
    void renameThatAlsoChangesOneSourceList() {
        classConceptEditModel.setNameModel(Texts.name("cs", "Nová třída"));
        classConceptEditModel.setRelatedNonLegalSource(List.of());

        editAndReplay();

        assertTdb2EqualsTheEditedModel();
        assertThat(model.listStatements(null, model.createProperty(
                "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/související-nelegislativní-zdroj"),
                (org.apache.jena.rdf.model.RDFNode) null).toList()).isEmpty();
    }

    @Test
    void editWithoutBlankChangesStillLeavesTheSources() {
        classConceptEditModel.setDescriptionModel(Texts.description("cs", "Popis"));

        ConceptEditor.EditResult result = editAndReplay();

        assertTdb2EqualsTheEditedModel();
        assertThat(result.statementsToAdd).noneMatch(s -> s.getObject().isAnon() || s.getSubject().isAnon());
    }

    @Test
    void unchangedEditStagesNothing() {
        classConceptEditModel.setNameModel(Texts.name("cs", "Třída"));
        classConceptEditModel.setDefiningNonLegalSource(List.of(new DigitalObjectModel("D1", null, "https://example.com/d1")));
        classConceptEditModel.setRelatedNonLegalSource(List.of(new DigitalObjectModel("R1", null, "https://example.com/r1")));

        ConceptEditor.EditResult result = editAndReplay();

        assertThat(result.statementsToAdd).isEmpty();
        assertThat(result.statementsToRemove).isEmpty();
        assertTdb2EqualsTheEditedModel();
    }
}
