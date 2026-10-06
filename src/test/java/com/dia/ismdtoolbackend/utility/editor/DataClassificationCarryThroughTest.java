package com.dia.ismdtoolbackend.utility.editor;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE_LEGAL;
import static com.dia.constants.VocabularyConstants.VEREJNY_UDAJ;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase C protects the one field where "leave it null" was NOT a safe no-op:
 * {@link ConceptFieldUpdaters#updateDataClassification} is called unconditionally by every type editor.
 * Its null guard now leaves the veřejný/neveřejný type untouched when nothing about the classification
 * is being edited (a null {@code isPublic} and no provisions), so a field-scoped edit — e.g. the diagram
 * overlay — can no longer silently strip the classification.
 *
 * <p>These tests pin that guard (so it is not silently removed) and prove the carry-through path —
 * {@code ConceptServiceImpl.carryCurrentDataClassification} passes the CURRENT value through when the
 * user did not accept the field.
 */
class DataClassificationCarryThroughTest {

    private final ConceptFieldUpdaters fields = new ConceptFieldUpdaters(new ConceptIriFactory());

    private record Fixture(Model model, Resource concept, Resource verejny,
                           Set<Statement> toRemove, Set<Statement> toAdd) {
    }

    /** A concept that currently IS a veřejný-údaj. */
    private Fixture publicConcept() {
        Model model = ModelFactory.createDefaultModel();
        Resource verejny = model.getResource(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ);
        Resource concept = model.getResource("https://example.org/slovnik/x/pojem/adresa");
        model.add(concept, RDF.type, verejny);
        return new Fixture(model, concept, verejny, new HashSet<>(), new HashSet<>());
    }

    private boolean removes(Fixture f) {
        return f.toRemove().stream().anyMatch(s -> s.getObject().equals(f.verejny()));
    }

    private boolean reAdds(Fixture f) {
        return f.toAdd().stream().anyMatch(s -> s.getObject().equals(f.verejny()));
    }

    @Test
    void nullIsPublic_leavesClassificationUntouched_theTrapPhaseCAvoids() {
        Fixture f = publicConcept();

        // A field-scoped edit that does not touch the classification (null isPublic, no provisions):
        // the guard must leave the veřejný type in place — neither removed nor re-added.
        fields.updateDataClassification(f.concept(), null, null, f.concept(), f.model(),
                f.toRemove(), f.toAdd());

        assertFalse(removes(f), "null isPublic must NOT remove the veřejný type — no silent data loss");
        assertFalse(reAdds(f), "…and there is nothing to re-add — the classification is left as-is");
    }

    @Test
    void carriedThroughIsPublic_preservesClassification() {
        Fixture f = publicConcept();

        // What Phase C actually does: carry the CURRENT value when the user did not accept the field.
        fields.updateDataClassification(f.concept(), Boolean.TRUE, null, f.concept(), f.model(),
                f.toRemove(), f.toAdd());

        assertTrue(reAdds(f), "carrying the current value re-adds the veřejný type, so the net effect is a no-op");
    }
}
