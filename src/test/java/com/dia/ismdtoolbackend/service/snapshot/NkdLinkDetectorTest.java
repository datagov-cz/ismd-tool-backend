package com.dia.ismdtoolbackend.service.snapshot;

import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.SnapshotLinkType;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which outgoing edges may point at a published NKD concept, and which are invalid input.
 *
 * <p>The rule is a matrix over (concept type × predicate), and the interesting cell is
 * <b>VZTAH + {@code rdfs:range}</b>. Everything else is unchanged from the original snapshot design:
 *
 * <table>
 *   <caption>External target handling</caption>
 *   <tr><th>Type</th><th>Predicate</th><th>Outcome</th></tr>
 *   <tr><td>any</td><td>{@code rdfs:domain}</td><td>rejected — a domain naming a published concept is
 *       invalid input whatever the subject is</td></tr>
 *   <tr><td>VLASTNOST</td><td>{@code rdfs:range}</td><td>rejected — a property's range is an XSD
 *       datatype, so a concept there is malformed</td></tr>
 *   <tr><td><b>VZTAH</b></td><td><b>{@code rdfs:range}</b></td><td><b>allowed</b>, snapshotted as
 *       {@code RANGE_TARGET} — a relationship's range IS a class</td></tr>
 *   <tr><td>TRIDA</td><td>{@code rdfs:subClassOf}</td><td>allowed (unchanged)</td></tr>
 *   <tr><td>any</td><td>{@code skos:exactMatch}</td><td>allowed (unchanged)</td></tr>
 * </table>
 *
 * <p>The carve-out is what lets a diagram draw a relationship from an owned class to an NKD one. Only
 * the VZTAH is written — it lives in the owner's graph and merely references the NKD IRI — so the NKD
 * concept's own triples are never touched.
 */
class NkdLinkDetectorTest {

    private static final String OWNER_GRAPH = "https://slovnik.gov.cz/mine";
    private static final String VZTAH = OWNER_GRAPH + "/pojem/je-zamestnan-u";
    private static final String PROP = OWNER_GRAPH + "/pojem/datum-narozeni";
    private static final String OWNED_CLASS = OWNER_GRAPH + "/pojem/zamestnanec";
    private static final String NKD_CLASS = "https://slovnik.gov.cz/legislativni/sbirka/pojem/osoba";

    private final NkdLinkDetector detector = new NkdLinkDetector();

    // ---- the carve-out --------------------------------------------------------------------------

    /** The change this step exists for: a VZTAH may point its range at a published NKD class. */
    @Test
    void vztahRangeToExternalConcept_isAllowedAndSnapshotted() {
        Model model = model(VZTAH, RDFS.range, NKD_CLASS);

        assertThat(detector.forbiddenDomainRangeTargets(VZTAH, ConceptType.VZTAH, OWNER_GRAPH, model))
                .as("a relationship's range is a class, so an external one is a link, not bad input")
                .isEmpty();
        assertThat(detector.allowedTargets(VZTAH, ConceptType.VZTAH, OWNER_GRAPH, model))
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.targetIri()).isEqualTo(NKD_CLASS);
                    assertThat(t.linkType()).isEqualTo(SnapshotLinkType.RANGE_TARGET);
                });
    }

    /** A VZTAH's DOMAIN is still invalid input — only the range side was carved out. */
    @Test
    void vztahDomainToExternalConcept_isStillForbidden() {
        Model model = model(VZTAH, RDFS.domain, NKD_CLASS);

        assertThat(detector.forbiddenDomainRangeTargets(VZTAH, ConceptType.VZTAH, OWNER_GRAPH, model))
                .containsExactly(NKD_CLASS);
        assertThat(detector.allowedTargets(VZTAH, ConceptType.VZTAH, OWNER_GRAPH, model))
                .as("a forbidden target is never also a snapshot candidate")
                .isEmpty();
    }

    /**
     * A VLASTNOST's range is an XSD datatype, so a concept there is malformed — the documented reason
     * range was excluded in the first place, and it still holds.
     */
    @Test
    void vlastnostRangeToExternalConcept_isStillForbidden() {
        Model model = model(PROP, RDFS.range, NKD_CLASS);

        assertThat(detector.forbiddenDomainRangeTargets(PROP, ConceptType.VLASTNOST, OWNER_GRAPH, model))
                .containsExactly(NKD_CLASS);
        assertThat(detector.allowedTargets(PROP, ConceptType.VLASTNOST, OWNER_GRAPH, model))
                .as("and it is NOT snapshotted either — the carve-out is VZTAH-only on both sides, or a "
                        + "property's datatype range would acquire a local copy as if it were a concept")
                .isEmpty();
    }

    /** The snapshot side is VZTAH-gated too: a TRIDA has no range, so nothing may be collected from one. */
    @Test
    void tridaWithARangeTriple_isNotSnapshottedAsARangeTarget() {
        Model model = model(OWNED_CLASS, RDFS.range, NKD_CLASS);

        assertThat(detector.allowedTargets(OWNED_CLASS, ConceptType.TRIDA, OWNER_GRAPH, model))
                .as("only a relationship's range names a class")
                .isEmpty();
    }

    /** A null type (an OFN upload with no matching OWL type) keeps the stricter pre-carve-out rule. */
    @Test
    void unknownType_treatsRangeConservativelyAsForbidden() {
        Model model = model(VZTAH, RDFS.range, NKD_CLASS);

        assertThat(detector.forbiddenDomainRangeTargets(VZTAH, null, OWNER_GRAPH, model))
                .as("without a type we cannot know the range is a class; stay strict")
                .containsExactly(NKD_CLASS);
    }

    /** An owned range is not external at all, so it is neither forbidden nor a snapshot candidate. */
    @Test
    void ownedRange_isNeitherForbiddenNorSnapshotted() {
        Model model = model(VZTAH, RDFS.range, OWNED_CLASS);

        assertThat(detector.forbiddenDomainRangeTargets(VZTAH, ConceptType.VZTAH, OWNER_GRAPH, model))
                .isEmpty();
        assertThat(detector.allowedTargets(VZTAH, ConceptType.VZTAH, OWNER_GRAPH, model)).isEmpty();
    }

    // ---- unchanged behaviour --------------------------------------------------------------------

    /** Hierarchy to a published NKD class was already supported and stays so. */
    @Test
    void tridaSubClassOfExternal_isStillSnapshottedAsBroaderClass() {
        Model model = model(OWNED_CLASS, RDFS.subClassOf, NKD_CLASS);

        assertThat(detector.allowedTargets(OWNED_CLASS, ConceptType.TRIDA, OWNER_GRAPH, model))
                .singleElement()
                .satisfies(t -> assertThat(t.linkType()).isEqualTo(SnapshotLinkType.BROADER_CLASS));
    }

    /** Equivalence to a published NKD concept was already supported and stays so. */
    @Test
    void exactMatchToExternal_isStillSnapshotted() {
        Model model = model(OWNED_CLASS, SKOS.exactMatch, NKD_CLASS);

        assertThat(detector.allowedTargets(OWNED_CLASS, ConceptType.TRIDA, OWNER_GRAPH, model))
                .singleElement()
                .satisfies(t -> assertThat(t.linkType()).isEqualTo(SnapshotLinkType.EXACT_MATCH));
    }

    /** A VZTAH may carry both a super-relation and an external range; both are snapshot candidates. */
    @Test
    void vztahWithSuperRelationAndExternalRange_yieldsBoth() {
        Model model = ModelFactory.createDefaultModel();
        model.add(model.createResource(VZTAH), RDFS.subPropertyOf, model.createResource(NKD_CLASS));
        model.add(model.createResource(VZTAH), RDFS.range,
                model.createResource("https://slovnik.gov.cz/legislativni/sbirka/pojem/organizace"));

        assertThat(detector.allowedTargets(VZTAH, ConceptType.VZTAH, OWNER_GRAPH, model))
                .extracting(NkdLinkDetector.LinkTarget::linkType)
                .containsExactlyInAnyOrder(SnapshotLinkType.SUPER_RELATION, SnapshotLinkType.RANGE_TARGET);
    }

    /** The new value must pass the snapshot service's own allow-check, which reads the enum. */
    @Test
    void rangeTargetIsAnAcceptedLinkPredicate() {
        assertThat(SnapshotLinkType.isAllowed(SnapshotLinkType.RANGE_TARGET.value())).isTrue();
        assertThat(SnapshotLinkType.fromValue("rangeTarget"))
                .contains(SnapshotLinkType.RANGE_TARGET);
    }

    private Model model(String subject, Property predicate, String object) {
        Model m = ModelFactory.createDefaultModel();
        m.add(m.createResource(subject), predicate, m.createResource(object));
        return m;
    }

    /** Guards the matrix above against a value being added without a decision about range handling. */
    @Test
    void linkTypesAreTheFiveSupportedRelations() {
        assertThat(List.of(SnapshotLinkType.values()))
                .containsExactlyInAnyOrder(
                        SnapshotLinkType.BROADER_CLASS, SnapshotLinkType.SUPER_PROPERTY,
                        SnapshotLinkType.SUPER_RELATION, SnapshotLinkType.EXACT_MATCH,
                        SnapshotLinkType.RANGE_TARGET);
    }
}
