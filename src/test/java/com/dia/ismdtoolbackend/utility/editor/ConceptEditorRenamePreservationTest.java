package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.NameModel;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Test;

import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE;
import static com.dia.constants.VocabularyConstants.VLASTNOST;
import static com.dia.constants.VocabularyConstants.VZTAH;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renaming a concept must carry ALL of its characteristics onto the new IRI,
 * not just the fields whose value changed.
 *
 * <p>The FE re-sends the whole concept on a rename (every field populated, most unchanged). The
 * field updaters write only what changed, so the rename itself copies every predicate; unchanged
 * characteristics such as domain, range and super-property therefore survive.
 */
class ConceptEditorRenamePreservationTest extends ConceptEditorTestBase {

    private static final String ONTOLOGY = "https://example.org/slovnik/test-slovnik";

    @Test
    void rename_property_preservesUnchangedDomainRangeAndSuperProperty() {
        String oldIri = ONTOLOGY + "/pojem/old-property";
        Resource scheme = model.createResource(ONTOLOGY);
        Property inScheme = model.createProperty("http://www.w3.org/2004/02/skos/core#inScheme");

        Resource existing = model.createResource(oldIri);
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + VLASTNOST));
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Old property", "cs"));
        existing.addProperty(inScheme, scheme);

        Resource domain = model.createResource(ONTOLOGY + "/pojem/trida-a");
        Resource range = model.createResource("http://www.w3.org/2001/XMLSchema#string");
        Resource superProp = model.createResource(ONTOLOGY + "/pojem/nadrazena-vlastnost");
        existing.addProperty(RDFS.domain, domain);
        existing.addProperty(RDFS.range, range);
        existing.addProperty(RDFS.subPropertyOf, superProp);

        // FE re-sends the whole concept: name changes, everything else unchanged.
        propertyConceptEditModel.setNameModel(createNameModel("cs", "New property"));
        // Unchanged values, re-sent exactly as stored.
        propertyConceptEditModel.setDomain(domain.getURI());
        propertyConceptEditModel.setDataType("string");
        propertyConceptEditModel.setSuperProperty(java.util.List.of(superProp.getURI()));

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(oldIri, propertyConceptEditModel, model, ONTOLOGY);

        assertTrue(result.iriChanged, "Rename precondition: IRI should have changed");
        Resource renamed = model.getResource(result.newConceptIRI);

        assertTrue(renamed.hasProperty(RDFS.domain, domain),
                "Unchanged rdfs:domain must survive a property rename");
        assertTrue(renamed.hasProperty(RDFS.range, range),
                "Unchanged rdfs:range must survive a property rename");
        assertTrue(renamed.hasProperty(RDFS.subPropertyOf, superProp),
                "Unchanged rdfs:subPropertyOf must survive a property rename");
        assertTrue(renamed.hasProperty(inScheme, scheme),
                "skos:inScheme must survive a property rename");

        Resource oldResource = model.getResource(oldIri);
        assertFalse(oldResource.hasProperty(RDFS.domain),
                "Old IRI must not retain domain after rename");
    }

    @Test
    void rename_relationship_preservesUnchangedDomainAndRange() {
        String oldIri = ONTOLOGY + "/pojem/old-rel";
        Resource scheme = model.createResource(ONTOLOGY);
        Property inScheme = model.createProperty("http://www.w3.org/2004/02/skos/core#inScheme");

        Resource existing = model.createResource(oldIri);
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + VZTAH));
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Old rel", "cs"));
        existing.addProperty(inScheme, scheme);

        Resource domain = model.createResource(ONTOLOGY + "/pojem/trida-a");
        Resource range = model.createResource(ONTOLOGY + "/pojem/trida-b");
        existing.addProperty(RDFS.domain, domain);
        existing.addProperty(RDFS.range, range);

        relationshipConceptEditModel.setNameModel(createNameModel("cs", "New rel"));
        relationshipConceptEditModel.setDomain(domain.getURI());
        relationshipConceptEditModel.setRange(range.getURI());

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(oldIri, relationshipConceptEditModel, model, ONTOLOGY);

        assertTrue(result.iriChanged);
        Resource renamed = model.getResource(result.newConceptIRI);

        assertTrue(renamed.hasProperty(RDFS.domain, domain),
                "Unchanged rdfs:domain must survive a relationship rename");
        assertTrue(renamed.hasProperty(RDFS.range, range),
                "Unchanged rdfs:range must survive a relationship rename");
        assertTrue(renamed.hasProperty(inScheme, scheme),
                "skos:inScheme must survive a relationship rename");
    }

    @Test
    void rename_class_preservesUnchangedDescriptionAndAltLabel() {
        String oldIri = ONTOLOGY + "/pojem/old-class";
        Resource scheme = model.createResource(ONTOLOGY);
        Property inScheme = model.createProperty("http://www.w3.org/2004/02/skos/core#inScheme");
        Property dctDescription = model.createProperty("http://purl.org/dc/terms/description");

        Resource existing = model.createResource(oldIri);
        existing.addProperty(RDF.type, SKOS.Concept);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Old class", "cs"));
        existing.addProperty(SKOS.altLabel, model.createLiteral("Alias", "cs"));
        existing.addProperty(dctDescription, model.createLiteral("A description", "cs"));
        existing.addProperty(inScheme, scheme);

        classConceptEditModel.setNameModel(createNameModel("cs", "New class"));
        // Re-send unchanged description + alt name.
        classConceptEditModel.setDescriptionModel(createDescriptionModel("cs", "A description"));
        classConceptEditModel.setAltNameModel(createAltNameModel("cs", "Alias"));

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(oldIri, classConceptEditModel, model, ONTOLOGY);

        assertTrue(result.iriChanged);
        Resource renamed = model.getResource(result.newConceptIRI);

        assertTrue(renamed.hasProperty(dctDescription, model.createLiteral("A description", "cs")),
                "Unchanged description must survive a class rename");
        assertTrue(renamed.hasProperty(SKOS.altLabel, model.createLiteral("Alias", "cs")),
                "Unchanged alt label must survive a class rename");
        assertTrue(renamed.hasProperty(inScheme, scheme),
                "skos:inScheme must survive a class rename");
    }
}