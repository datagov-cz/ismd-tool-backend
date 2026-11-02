package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.NameModel;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static com.dia.constants.VocabularyConstants.*;

/** Edit behavior shared by every concept type: IRI stability and null-means-unchanged. */
class ConceptEditorCrossCuttingTest extends ConceptEditorTestBase {
    @Test
    void editConcept_ShouldNotChangeIRI_WhenNameStaysTheSame() {
        String conceptIri = DEFAULT_NS + "class-d1";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Same name", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TOP));

        NameModel sameName = createNameModel("cs", "Same name");
        classConceptEditModel.setNameModel(sameName);
        classConceptEditModel.setType("subjekt");

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        assertThat(result.iriChanged).isFalse();
        assertThat(result.newConceptIRI).isEqualTo(conceptIri);

        Resource updated = model.getResource(conceptIri);
        Resource tspType = model.getResource(OFN_NAMESPACE + TSP);
        Resource topType = model.getResource(OFN_NAMESPACE + TOP);
        assertThat(updated.hasProperty(RDF.type, tspType)).isTrue();
        assertThat(updated.hasProperty(RDF.type, topType)).isFalse();
    }
    @Test
    void editConcept_ShouldUseIdentifier_WhenIdentifierIsNonNull() {
        String oldIri = DEFAULT_NS + "class-d1b";
        Resource existing = model.createResource(oldIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Old name", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        NameModel newName = createNameModel("cs", "New name d1b");
        classConceptEditModel.setNameModel(newName);
        classConceptEditModel.setIdentifier("CUSTOM-ID-123");

        String expectedIri = DEFAULT_NS + "pojem/custom-id-123";

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(oldIri, classConceptEditModel, model, null);

        assertThat(result.iriChanged).isTrue();
        assertThat(result.newConceptIRI).isEqualTo(expectedIri);
    }
    @Test
    void editConcept_ShouldNotRemoveExistingData_WhenEditFieldsAreNull() {
        String conceptIri = DEFAULT_NS + "class-d2";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class d2", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TOP));

        Property descProperty = model.createProperty("http://purl.org/dc/terms/description");
        existing.addProperty(descProperty, model.createLiteral("Existing description", "cs"));
        existing.addProperty(SKOS.definition, model.createLiteral("Existing definition", "cs"));
        existing.addProperty(SKOS.altLabel, model.createLiteral("Existing alt", "cs"));

        Property exactMatchProp = model.createProperty("http://www.w3.org/2004/02/skos/core#exactMatch");
        existing.addProperty(exactMatchProp, model.createResource("https://example.com/existing-match"));

        Property ppdfProperty = model.createProperty(DEFAULT_NS + AGENDOVY_104 + JE_PPDF_LONG);
        existing.addProperty(ppdfProperty, model.createLiteral("true"));

        long originalStatementCount = model.size();

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        assertThat(result.iriChanged).isFalse();
        assertThat(result.changesCount).isEqualTo(0);

        Resource updated = model.getResource(conceptIri);
        assertThat(updated.hasProperty(SKOS.prefLabel)).isTrue();
        assertThat(updated.hasProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA))).isTrue();
        assertThat(updated.hasProperty(RDF.type, model.getResource(OFN_NAMESPACE + TOP))).isTrue();
        assertThat(updated.hasProperty(descProperty)).isTrue();
        assertThat(updated.hasProperty(SKOS.definition)).isTrue();
        assertThat(updated.hasProperty(SKOS.altLabel)).isTrue();
        assertThat(updated.hasProperty(exactMatchProp)).isTrue();
        assertThat(updated.hasProperty(ppdfProperty)).isTrue();
        assertThat(model.size()).isEqualTo(originalStatementCount);
    }
}
