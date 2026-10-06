package com.dia.ismdtoolbackend.utility.editor;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.dia.constants.VocabularyConstants.NEVEREJNY_UDAJ;
import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE_LEGAL;
import static com.dia.constants.VocabularyConstants.VEREJNY_UDAJ;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the field-scoped classification edit (V4). A diagram-overlay materialize builds a
 * {@code ConceptEditModel} carrying only the changed structural predicates, so {@code isPublic} arrives
 * {@code null}. Without the null-safe guard {@code updateDataClassification} strips the veřejný/neveřejný
 * {@code rdf:type} and never re-adds it, silently dropping the classification. These tests lock the guard
 * in and confirm the normal (non-null) paths still transition correctly.
 *
 * <p>Same package as the target so the package-private updater is callable directly — no Spring context.
 */
class ConceptFieldUpdatersClassificationTest {

    private final ConceptFieldUpdaters updaters = new ConceptFieldUpdaters(new ConceptIriFactory());

    private final Resource verejny = ModelFactory.createDefaultModel()
            .getResource(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ);
    private final Resource neverejny = ModelFactory.createDefaultModel()
            .getResource(OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ);

    /**
     * The core V4 fix. An existing veřejný-údaj concept, edited with {@code isPublic == null} and no
     * provisions (a field-scoped structural edit), must NOT have its classification touched.
     */
    @Test
    void nullIsPublicWithNoProvisions_leavesExistingClassificationUntouched() {
        Model model = ModelFactory.createDefaultModel();
        Resource verejnyType = model.getResource(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ);
        Resource concept = model.createResource("https://x/pojem/zamestnanec");
        concept.addProperty(RDF.type, verejnyType);   // already classified public

        Set<Statement> toRemove = new HashSet<>();
        Set<Statement> toAdd = new HashSet<>();

        updaters.updateDataClassification(concept, null, null, concept, model, toRemove, toAdd);

        // Nothing about the classification is being edited — no strip, no re-add.
        assertThat(toRemove).isEmpty();
        assertThat(toAdd).isEmpty();
    }

    /** Same guard, with an all-empty/blank provisions list — still a no-op. */
    @Test
    void nullIsPublicWithBlankProvisions_leavesExistingClassificationUntouched() {
        Model model = ModelFactory.createDefaultModel();
        Resource neverejnyType = model.getResource(OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ);
        Resource concept = model.createResource("https://x/pojem/plat");
        concept.addProperty(RDF.type, neverejnyType);

        Set<Statement> toRemove = new HashSet<>();
        Set<Statement> toAdd = new HashSet<>();

        updaters.updateDataClassification(concept, null, List.of("", "   "), concept, model, toRemove, toAdd);

        assertThat(toRemove).isEmpty();
        assertThat(toAdd).isEmpty();
    }

    /** A genuine transition to public (isPublic == TRUE) still re-adds the veřejný type. */
    @Test
    void isPublicTrue_addsVerejnyType() {
        Model model = ModelFactory.createDefaultModel();
        Resource concept = model.createResource("https://x/pojem/nova");

        Set<Statement> toRemove = new HashSet<>();
        Set<Statement> toAdd = new HashSet<>();

        updaters.updateDataClassification(concept, Boolean.TRUE, null, concept, model, toRemove, toAdd);

        assertThat(toAdd).anyMatch(s -> s.getPredicate().equals(RDF.type) && s.getObject().equals(verejny));
    }

    /**
     * A transition to public over an existing private concept strips the neveřejný type and adds veřejný —
     * proves the strip path still fires when the classification IS being edited.
     */
    @Test
    void isPublicTrue_overExistingPrivate_stripsNeverejnyAndAddsVerejny() {
        Model model = ModelFactory.createDefaultModel();
        Resource neverejnyType = model.getResource(OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ);
        Resource concept = model.createResource("https://x/pojem/prevod");
        concept.addProperty(RDF.type, neverejnyType);

        Set<Statement> toRemove = new HashSet<>();
        Set<Statement> toAdd = new HashSet<>();

        updaters.updateDataClassification(concept, Boolean.TRUE, null, concept, model, toRemove, toAdd);

        assertThat(toRemove).anyMatch(s -> s.getPredicate().equals(RDF.type) && s.getObject().equals(neverejny));
        assertThat(toAdd).anyMatch(s -> s.getPredicate().equals(RDF.type) && s.getObject().equals(verejny));
    }
}
