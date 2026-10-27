package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dia.constants.VocabularyConstants.*;
import static org.apache.jena.vocabulary.RDF.type;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Privacy provisions belong to every concept type (class, property, relationship), as on create.
 * Property and relationship edits write valid provisions and reject invalid ones.
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

        propertyConceptEditModel.setPrivacyProvisions(List.of(VALID_ELI));
        propertyConceptEditModel.setIsPublic(Boolean.FALSE);

        conceptEditor.editConcept(iri, propertyConceptEditModel, model, null);

        Resource updated = model.getResource(iri);
        assertThat(updated.hasProperty(provisionProp(), model.createResource(VALID_ELI)))
                .as("property edit must persist the privacy provision IRI")
                .isTrue();
    }

    @Test
    void relationshipEdit_writesPrivacyProvisions() {
        String iri = DEFAULT_NS + "rel-priv";
        seedRelationship(iri);

        relationshipConceptEditModel.setPrivacyProvisions(List.of(VALID_ELI));
        relationshipConceptEditModel.setIsPublic(Boolean.FALSE);

        conceptEditor.editConcept(iri, relationshipConceptEditModel, model, null);

        Resource updated = model.getResource(iri);
        assertThat(updated.hasProperty(provisionProp(), model.createResource(VALID_ELI)))
                .as("relationship edit must persist the privacy provision IRI")
                .isTrue();
    }

    @Test
    void propertyEdit_rejectsInvalidPrivacyProvision() {
        String iri = DEFAULT_NS + "prop-priv-bad";
        seedProperty(iri);
        long sizeBefore = model.size();

        propertyConceptEditModel.setPrivacyProvisions(List.of(INVALID_ELI));

        ConceptValidationException ex = assertThatExceptionOfType(ConceptValidationException.class)
                .isThrownBy(() ->
                conceptEditor.editConcept(iri, propertyConceptEditModel, model, null))
                .actual();
        assertThat(ex).as(ex.getMessage()).hasMessageContaining("privacyProvisions");
        assertThat(model.size()).as("rejected edit must not mutate the model").isEqualTo(sizeBefore);
    }

    @Test
    void relationshipEdit_rejectsInvalidPrivacyProvision() {
        String iri = DEFAULT_NS + "rel-priv-bad";
        seedRelationship(iri);
        long sizeBefore = model.size();

        relationshipConceptEditModel.setPrivacyProvisions(List.of(INVALID_ELI));

        ConceptValidationException ex = assertThatExceptionOfType(ConceptValidationException.class)
                .isThrownBy(() ->
                conceptEditor.editConcept(iri, relationshipConceptEditModel, model, null))
                .actual();
        assertThat(ex).as(ex.getMessage()).hasMessageContaining("privacyProvisions");
        assertThat(model.size()).as("rejected edit must not mutate the model").isEqualTo(sizeBefore);
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
        propertyConceptEditModel.setPrivacyProvisions(provisions);
        propertyConceptEditModel.setIsPublic(Boolean.FALSE);
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
        assertThat(objects(updated, provisionProp())).isEqualTo(java.util.Set.of(OTHER_ELI));
        assertThat(objects(updated, namespaceCopy()))
                .as("the stale provision must not survive under the vocabulary-namespace property")
                .isEqualTo(java.util.Set.of(OTHER_ELI));
    }

    @Test
    void edit_clearingTheProvisionsClearsEveryCopy() {
        String iri = DEFAULT_NS + "prop-clear";
        Resource concept = seedProperty(iri);
        concept.addProperty(provisionProp(), model.createResource(VALID_ELI));
        concept.addProperty(namespaceCopy(), model.createResource(VALID_ELI));

        editProvisions(iri, List.of());

        Resource updated = model.getResource(iri);
        assertThat(objects(updated, provisionProp())).isEmpty();
        assertThat(objects(updated, namespaceCopy())).isEmpty();
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
        assertThat(updated.hasProperty(foreignNamespace)).isFalse();
        assertThat(updated.hasProperty(canonicalOfn)).isFalse();
        assertThat(objects(updated, provisionProp())).isEqualTo(java.util.Set.of(OTHER_ELI));
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
        assertThat(lastResult.statementsToRemove.stream().noneMatch(aboutProvisions)).isTrue();
        assertThat(lastResult.statementsToAdd.stream().noneMatch(aboutProvisions)).isTrue();
    }

    // --- Non-public classification ------------------------------------------

    private Resource nonPublicType() {
        return model.getResource(OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ);
    }

    /** An edit that changes something else must not strip the classification. */
    @Test
    void edit_withUnchangedProvisionsKeepsTheNonPublicType() {
        String iri = DEFAULT_NS + "prop-keeps-type";
        Resource concept = seedProperty(iri);
        concept.addProperty(type, nonPublicType());
        concept.addProperty(provisionProp(), model.createResource(VALID_ELI));
        concept.addProperty(namespaceCopy(), model.createResource(VALID_ELI));

        editProvisions(iri, List.of(VALID_ELI));

        assertThat(model.getResource(iri).hasProperty(type, nonPublicType())).isTrue();
        assertThat(lastResult.statementsToRemove.stream().noneMatch(s -> s.getObject().equals(nonPublicType())))
                .isTrue();
        assertThat(lastResult.statementsToAdd.stream().noneMatch(s -> s.getObject().equals(nonPublicType()))).isTrue();
    }

    @Test
    void edit_isPublicFalseWithoutTouchingProvisionsKeepsTheNonPublicType() {
        String iri = DEFAULT_NS + "prop-flag-only";
        Resource concept = seedProperty(iri);
        concept.addProperty(type, nonPublicType());
        concept.addProperty(provisionProp(), model.createResource(VALID_ELI));

        editProvisions(iri, null);

        assertThat(model.getResource(iri).hasProperty(type, nonPublicType())).isTrue();
    }

    @Test
    void edit_clearingTheProvisionsRemovesTheNonPublicType() {
        String iri = DEFAULT_NS + "prop-loses-type";
        Resource concept = seedProperty(iri);
        concept.addProperty(type, nonPublicType());
        concept.addProperty(provisionProp(), model.createResource(VALID_ELI));

        editProvisions(iri, List.of());

        assertThat(model.getResource(iri).hasProperty(type, nonPublicType())).isFalse();
    }

    @Test
    void edit_addingAProvisionAddsTheNonPublicType() {
        String iri = DEFAULT_NS + "prop-gains-type";
        seedProperty(iri);

        editProvisions(iri, List.of(VALID_ELI));

        assertThat(model.getResource(iri).hasProperty(type, nonPublicType())).isTrue();
    }
}
