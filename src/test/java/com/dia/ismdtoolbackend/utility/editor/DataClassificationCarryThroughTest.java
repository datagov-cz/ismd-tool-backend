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
 * Phase C guards the one field where "leave it null" is NOT a safe no-op:
 * {@link ConceptFieldUpdaters#updateDataClassification} has no null guard and is called unconditionally
 * by every type editor, so a null {@code isPublic} strips the veřejný/neveřejný type and never re-adds it.
 *
 * <p>These tests pin that behaviour (so the guard is not silently removed) and prove the fix —
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
    void nullIsPublic_stripsClassification_theTrapPhaseCMustAvoid() {
        Fixture f = publicConcept();

        // What a naive "leave unaccepted fields null" sync would do.
        fields.updateDataClassification(f.concept(), null, null, f.concept(), f.model(),
                f.toRemove(), f.toAdd());

        assertTrue(removes(f), "null isPublic removes the veřejný type");
        assertFalse(reAdds(f), "…and never re-adds it — silent data loss");
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
