package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dia.constants.VocabularyConstants.*;
import static org.apache.jena.vocabulary.RDF.type;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Regression: privacy provisions are carried by ALL concept types (class,
 * property, relationship) — each can be public/non-public. The create path
 * (ConceptCreator) already writes them for all three; the edit path previously
 * only wrote them for class concepts, silently losing provisions on property /
 * relationship edits. These tests pin that property and relationship edits both
 * WRITE valid provisions and REJECT invalid ones.
 */
class ConceptEditorPrivacyProvisionsTest extends ConceptEditorTestBase {

    private static final String VALID_ELI =
            "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187";
    private static final String INVALID_ELI = "https://example.org/not-eli";

    private Property provisionProp() {
        return model.createProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST);
    }

    private Resource seedProperty(String iri) {
        Resource r = model.createResource(iri);
        r.addProperty(org.apache.jena.vocabulary.SKOS.prefLabel, model.createLiteral("Vlastnost", "cs"));
        r.addProperty(type, model.getResource(OFN_NAMESPACE + VLASTNOST));
        return r;
    }

    private Resource seedRelationship(String iri) {
        Resource r = model.createResource(iri);
        r.addProperty(org.apache.jena.vocabulary.SKOS.prefLabel, model.createLiteral("Vztah", "cs"));
        r.addProperty(type, model.getResource(OFN_NAMESPACE + VZTAH));
        return r;
    }

    @Test
    void propertyEdit_writesPrivacyProvisions() {
        String iri = DEFAULT_NS + "prop-priv";
        seedProperty(iri);

        stubAllPropertyFieldsNull(propertyConceptEditModel);
        when(propertyConceptEditModel.getPrivacyProvisions()).thenReturn(List.of(VALID_ELI));
        when(propertyConceptEditModel.getIsPublic()).thenReturn(Boolean.FALSE);

        conceptEditor.editConcept(iri, propertyConceptEditModel, model, null);

        Resource updated = model.getResource(iri);
        assertTrue(updated.hasProperty(provisionProp(), model.createResource(VALID_ELI)),
                "property edit must persist the privacy provision IRI");
    }

    @Test
    void relationshipEdit_writesPrivacyProvisions() {
        String iri = DEFAULT_NS + "rel-priv";
        seedRelationship(iri);

        stubAllRelationshipFieldsNull(relationshipConceptEditModel);
        when(relationshipConceptEditModel.getPrivacyProvisions()).thenReturn(List.of(VALID_ELI));
        when(relationshipConceptEditModel.getIsPublic()).thenReturn(Boolean.FALSE);

        conceptEditor.editConcept(iri, relationshipConceptEditModel, model, null);

        Resource updated = model.getResource(iri);
        assertTrue(updated.hasProperty(provisionProp(), model.createResource(VALID_ELI)),
                "relationship edit must persist the privacy provision IRI");
    }

    @Test
    void propertyEdit_rejectsInvalidPrivacyProvision() {
        String iri = DEFAULT_NS + "prop-priv-bad";
        seedProperty(iri);
        long sizeBefore = model.size();

        lenient().when(propertyConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.VLASTNOST);
        lenient().when(propertyConceptEditModel.getPrivacyProvisions()).thenReturn(List.of(INVALID_ELI));
        lenient().when(propertyConceptEditModel.getDefiningLegalSource()).thenReturn(null);
        lenient().when(propertyConceptEditModel.getRelatedLegalSource()).thenReturn(null);
        lenient().when(propertyConceptEditModel.getDefiningNonLegalSource()).thenReturn(null);
        lenient().when(propertyConceptEditModel.getRelatedNonLegalSource()).thenReturn(null);
        lenient().when(propertyConceptEditModel.getExactMatch()).thenReturn(null);
        lenient().when(propertyConceptEditModel.getAgendaCode()).thenReturn(null);
        lenient().when(propertyConceptEditModel.getAgendaSystemCode()).thenReturn(null);

        ConceptValidationException ex = assertThrows(ConceptValidationException.class, () ->
                conceptEditor.editConcept(iri, propertyConceptEditModel, model, null));
        assertTrue(ex.getMessage().contains("privacyProvisions"), ex.getMessage());
        assertEquals(sizeBefore, model.size(), "rejected edit must not mutate the model");
    }

    @Test
    void relationshipEdit_rejectsInvalidPrivacyProvision() {
        String iri = DEFAULT_NS + "rel-priv-bad";
        seedRelationship(iri);
        long sizeBefore = model.size();

        lenient().when(relationshipConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.VZTAH);
        lenient().when(relationshipConceptEditModel.getPrivacyProvisions()).thenReturn(List.of(INVALID_ELI));
        lenient().when(relationshipConceptEditModel.getDefiningLegalSource()).thenReturn(null);
        lenient().when(relationshipConceptEditModel.getRelatedLegalSource()).thenReturn(null);
        lenient().when(relationshipConceptEditModel.getDefiningNonLegalSource()).thenReturn(null);
        lenient().when(relationshipConceptEditModel.getRelatedNonLegalSource()).thenReturn(null);
        lenient().when(relationshipConceptEditModel.getExactMatch()).thenReturn(null);
        lenient().when(relationshipConceptEditModel.getAgendaCode()).thenReturn(null);
        lenient().when(relationshipConceptEditModel.getAgendaSystemCode()).thenReturn(null);

        ConceptValidationException ex = assertThrows(ConceptValidationException.class, () ->
                conceptEditor.editConcept(iri, relationshipConceptEditModel, model, null));
        assertTrue(ex.getMessage().contains("privacyProvisions"), ex.getMessage());
        assertEquals(sizeBefore, model.size(), "rejected edit must not mutate the model");
    }

    // --- Other stored copies of the provisions ------------------------------

    private static final String OTHER_ELI =
            "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2005/348";

    /** The copy create writes under the vocabulary's own namespace. */
    private Property namespaceCopy() {
        return model.createProperty(DEFAULT_NS + USTANOVENI_NEVEREJNOST);
    }

    private java.util.Set<String> objects(Resource subject, Property property) {
        return subject.listProperties(property).mapWith(s -> s.getObject().asResource().getURI()).toSet();
    }

    private void editProvisions(String iri, List<String> provisions) {
        stubAllPropertyFieldsNull(propertyConceptEditModel);
        when(propertyConceptEditModel.getPrivacyProvisions()).thenReturn(provisions);
        when(propertyConceptEditModel.getIsPublic()).thenReturn(Boolean.FALSE);
        lastResult = conceptEditor.editConcept(iri, propertyConceptEditModel, model, null);
    }

    private ConceptEditor.EditResult lastResult;

    @Test
    void edit_replacesTheNamespaceCopyAlongWithTheProvisions() {
        String iri = DEFAULT_NS + "prop-copy";
        Resource concept = seedProperty(iri);
        concept.addProperty(provisionProp(), model.createResource(VALID_ELI));
        concept.addProperty(namespaceCopy(), model.createResource(VALID_ELI));

        editProvisions(iri, List.of(OTHER_ELI));

        Resource updated = model.getResource(iri);
        assertEquals(java.util.Set.of(OTHER_ELI), objects(updated, provisionProp()));
        assertEquals(java.util.Set.of(OTHER_ELI), objects(updated, namespaceCopy()),
                "the stale provision must not survive under the vocabulary-namespace property");
    }

    @Test
    void edit_clearingTheProvisionsClearsEveryCopy() {
        String iri = DEFAULT_NS + "prop-clear";
        Resource concept = seedProperty(iri);
        concept.addProperty(provisionProp(), model.createResource(VALID_ELI));
        concept.addProperty(namespaceCopy(), model.createResource(VALID_ELI));

        editProvisions(iri, List.of());

        Resource updated = model.getResource(iri);
        assertTrue(objects(updated, provisionProp()).isEmpty());
        assertTrue(objects(updated, namespaceCopy()).isEmpty());
    }

    @Test
    void edit_dropsCopiesUploadedUnderOtherPropertyNames() {
        String iri = DEFAULT_NS + "prop-uploaded";
        Resource concept = seedProperty(iri);
        Property foreignNamespace = model.createProperty(
                "https://slovník.gov.cz/jiný-slovník/" + USTANOVENI_NEVEREJNOST);
        Property canonicalOfn = model.createProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_LONG);
        concept.addProperty(foreignNamespace, model.createResource(VALID_ELI));
        concept.addProperty(canonicalOfn, model.createResource(VALID_ELI));

        editProvisions(iri, List.of(OTHER_ELI));

        Resource updated = model.getResource(iri);
        assertFalse(updated.hasProperty(foreignNamespace));
        assertFalse(updated.hasProperty(canonicalOfn));
        assertEquals(java.util.Set.of(OTHER_ELI), objects(updated, provisionProp()));
    }

    /** An unchanged edit must stage nothing: a staged change bumps updatedAt. */
    @Test
    void edit_withUnchangedProvisionsStagesNoProvisionChange() {
        String iri = DEFAULT_NS + "prop-same";
        Resource concept = seedProperty(iri);
        concept.addProperty(provisionProp(), model.createResource(VALID_ELI));
        concept.addProperty(namespaceCopy(), model.createResource(VALID_ELI));

        editProvisions(iri, List.of(VALID_ELI));

        java.util.function.Predicate<org.apache.jena.rdf.model.Statement> aboutProvisions =
                s -> s.getPredicate().getURI().endsWith(USTANOVENI_NEVEREJNOST);
        assertTrue(lastResult.statementsToRemove.stream().noneMatch(aboutProvisions));
        assertTrue(lastResult.statementsToAdd.stream().noneMatch(aboutProvisions));
    }
}
