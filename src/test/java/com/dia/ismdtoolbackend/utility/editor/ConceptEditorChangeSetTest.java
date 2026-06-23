package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.enums.ConceptType;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Test;

import static com.dia.constants.VocabularyConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * T1 (outbox plan) — pins {@link ConceptEditor.EditResult}'s {@code statementsToRemove} /
 * {@code statementsToAdd} as a FAITHFUL diff of the edit. The outbox write path (T4/T5) enqueues a
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
        assertTrue(rebuilt.isIsomorphicWith(postEdit),
                "Applying the change sets to the pre-edit graph must reproduce the editor's mutation.\n"
                        + "Missing from rebuilt: " + postEdit.difference(rebuilt) + "\n"
                        + "Extra in rebuilt: " + rebuilt.difference(postEdit));
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

        when(classConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
        when(classConceptEditModel.getNameModel()).thenReturn(null); // no rename
        when(classConceptEditModel.getDefinitionModel()).thenReturn(createDefinitionModel("cs", "New definition"));
        when(classConceptEditModel.getIsPublic()).thenReturn(null);
        when(classConceptEditModel.getIsInPPDF()).thenReturn(null);

        ConceptEditor.EditResult result = conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        assertFalse(result.iriChanged);
        assertFalse(result.statementsToRemove.isEmpty() && result.statementsToAdd.isEmpty(),
                "A real field change must produce a non-empty delta");
        assertEquals(result.statementsToRemove.size() + result.statementsToAdd.size(), result.changesCount);
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
        when(classConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
        when(classConceptEditModel.getNameModel()).thenReturn(newName);
        when(classConceptEditModel.getIdentifier()).thenReturn("ID-1");
        when(classConceptEditModel.getIsPublic()).thenReturn(null);
        when(classConceptEditModel.getIsInPPDF()).thenReturn(null);

        ConceptEditor.EditResult result = conceptEditor.editConcept(oldIri, classConceptEditModel, model, null);

        assertTrue(result.iriChanged);
        // The incoming edge's relocation must be captured in the delta (it's an add onto the new IRI
        // and a remove of the old-IRI-targeting triple), proving the delta covers incoming edges.
        Resource renamed = model.getResource(result.newConceptIRI);
        boolean addsIncomingRelocation = result.statementsToAdd.stream()
                .anyMatch(s -> s.getPredicate().equals(RDFS.domain) && renamed.equals(s.getObject()));
        assertTrue(addsIncomingRelocation, "Rename delta must relocate the incoming rdfs:domain edge onto the new IRI");

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

        stubAllClassFieldsNull(classConceptEditModel);

        ConceptEditor.EditResult result = conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        assertFalse(result.iriChanged);
        assertTrue(result.statementsToRemove.isEmpty());
        assertTrue(result.statementsToAdd.isEmpty());
        assertEquals(0, result.changesCount);
        assertDeltaReproducesEdit(preEdit, model, result);
    }
}