package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.NameModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Test;

import static com.dia.constants.VocabularyConstants.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link ConceptEditor.EditResult}'s {@code statementsToRemove} /
 * {@code statementsToAdd} as a faithful diff of the edit. The outbox write path enqueues a
 * concept-scoped {@code DELETE(remove)/INSERT(add)} delta built from these sets instead of a
 * whole-graph replace, so the sets must reproduce the editor's in-place mutation exactly — applying
 * them to the pre-edit graph must yield the post-edit graph. If this drifts, the relay would write
 * a different graph than the synchronous path did.
 */
class ConceptEditorChangeSetTest extends ConceptEditorTestBase {

    /** Applies the editor's change sets to {@code preEdit} and asserts the result is isomorphic to the editor's in-place mutation ({@code postEdit}). */
    private void assertDeltaReproducesEdit(Model preEdit, Model postEdit, ConceptEditor.EditResult result) {
        Model rebuilt = ModelFactory.createDefaultModel().add(preEdit);
        rebuilt.remove(result.statementsToRemove.toArray(new Statement[0]));
        rebuilt.add(result.statementsToAdd.toArray(new Statement[0]));
        assertThat(rebuilt.isIsomorphicWith(postEdit))
                .as("Applying the change sets to the pre-edit graph must reproduce the editor's mutation.\n"
                        + "Missing from rebuilt: " + postEdit.difference(rebuilt) + "\n"
                        + "Extra in rebuilt: " + rebuilt.difference(postEdit))
                .isTrue();
    }

    // Field-only edit (no rename): the delta must reproduce the mutation and touch only changed triples.
    @Test
    void changeSets_ReproduceEdit_OnFieldOnlyEdit() {
        String conceptIri = DEFAULT_NS + "class-1";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));
        existing.addProperty(SKOS.definition, model.createLiteral("Old definition", "cs"));

        Model preEdit = ModelFactory.createDefaultModel().add(model);

        classConceptEditModel.setDefinitionModel(createDefinitionModel("cs", "New definition"));

        ConceptEditor.EditResult result = conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        assertThat(result.iriChanged).isFalse();
        assertThat(result.statementsToRemove.isEmpty() && result.statementsToAdd.isEmpty())
                .as("A real field change must produce a non-empty delta")
                .isFalse();
        assertThat(result.changesCount).isEqualTo(result.statementsToRemove.size() + result.statementsToAdd.size());
        assertDeltaReproducesEdit(preEdit, model, result);
    }

    // Rename: the delta is concept-PAIR-scoped — it must also relocate an INCOMING edge from another
    // concept (rdfs:domain → old IRI) onto the new IRI. This is the case the outbox op must carry as
    // exact serialized triples, never a `<iri> ?p ?o` wildcard.
    @Test
    void changeSets_ReproduceEdit_OnRename_IncludingIncomingEdges() {
        String oldIri = "https://slovnik.gov.cz/pojem/old-class";
        Resource existing = model.createResource(oldIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Old name", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        // An incoming edge: another concept points at the one being renamed.
        Resource other = model.createResource("https://slovnik.gov.cz/pojem/other");
        other.addProperty(RDFS.domain, existing);

        Model preEdit = ModelFactory.createDefaultModel().add(model);

        NameModel newName = createNameModel("cs", "New name");
        classConceptEditModel.setNameModel(newName);
        classConceptEditModel.setIdentifier("ID-1");

        ConceptEditor.EditResult result = conceptEditor.editConcept(oldIri, classConceptEditModel, model, null);

        assertThat(result.iriChanged).isTrue();
        // The incoming edge's relocation must be captured in the delta (it's an add onto the new IRI
        // and a remove of the old-IRI-targeting triple), proving the delta covers incoming edges.
        Resource renamed = model.getResource(result.newConceptIRI);
        boolean addsIncomingRelocation = result.statementsToAdd.stream()
                .anyMatch(s -> s.getPredicate().equals(RDFS.domain) && renamed.equals(s.getObject()));
        assertThat(addsIncomingRelocation)
                .as("Rename delta must relocate the incoming rdfs:domain edge onto the new IRI")
                .isTrue();

        assertDeltaReproducesEdit(preEdit, model, result);
    }

    // No-op edit (nothing changes): delta is empty and reproduces (trivially) the unchanged graph.
    @Test
    void changeSets_Empty_OnNoOpEdit() {
        String conceptIri = DEFAULT_NS + "class-1";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        Model preEdit = ModelFactory.createDefaultModel().add(model);

        ConceptEditor.EditResult result = conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        assertThat(result.iriChanged).isFalse();
        assertThat(result.statementsToRemove).isEmpty();
        assertThat(result.statementsToAdd).isEmpty();
        assertThat(result.changesCount).isEqualTo(0);
        assertDeltaReproducesEdit(preEdit, model, result);
    }
}