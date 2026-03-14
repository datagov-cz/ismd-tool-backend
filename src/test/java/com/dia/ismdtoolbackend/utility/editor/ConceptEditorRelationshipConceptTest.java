package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.enums.ConceptType;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static com.dia.constants.VocabularyConstants.*;

/**
 * C — RelationshipConcept (VZTAH) tests
 *   C1 – Domain / range / superrelation and PPDF flag
 *   C2 – Governance content, sharing and acquisition for relations
 *   C3 – IRI rename for VZTAH type
 */
class ConceptEditorRelationshipConceptTest extends ConceptEditorTestBase {

    // C1/C2 – Relationship-specific fields, domain/range, governance and PPDF
    @Test
    void editConcept_ShouldUpdateRelationshipSpecificFields() {
        String conceptIri = DEFAULT_NS + "rel-1";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Relationship", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + VZTAH));

        Resource oldDomain = model.createResource("https://example.com/old-rel-domain");
        existing.addProperty(RDFS.domain, oldDomain);

        Resource oldRange = model.createResource("https://example.com/old-rel-range");
        existing.addProperty(RDFS.range, oldRange);

        Resource oldSuper = model.createResource("https://example.com/old-rel-super");
        existing.addProperty(RDFS.subPropertyOf, oldSuper);

        Property ppdfProperty = model.createProperty(DEFAULT_NS + AGENDOVY_104 + JE_PPDF_LONG);
        existing.addProperty(ppdfProperty, model.createLiteral("false"));

        Property contentTypeProperty = model.createProperty(OFN_NAMESPACE + TYP_OBSAHU);
        existing.addProperty(
                contentTypeProperty,
                model.createResource(
                        "https://data.dia.gov.cz/zdroj/číselníky/typy-obsahu-údajů/položky/old"
                )
        );

        Property sharingProperty = model.createProperty(OFN_NAMESPACE + ZPUSOB_SDILENI);
        existing.addProperty(
                sharingProperty,
                model.createResource(
                        "https://data.dia.gov.cz/zdroj/číselníky/způsoby-sdílení-údajů/položky/old"
                )
        );

        Property acquisitionProperty = model.createProperty(OFN_NAMESPACE + ZPUSOB_ZISKANI);
        existing.addProperty(
                acquisitionProperty,
                model.createResource(
                        "https://data.dia.gov.cz/zdroj/číselníky/způsoby-získání-údajů/položky/old"
                )
        );

        when(relationshipConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.VZTAH);
        when(relationshipConceptEditModel.getNameModel()).thenReturn(null);
        when(relationshipConceptEditModel.getDescriptionModel()).thenReturn(null);
        when(relationshipConceptEditModel.getDefinitionModel()).thenReturn(null);
        when(relationshipConceptEditModel.getAltNameModel()).thenReturn(null);

        when(relationshipConceptEditModel.getDomain()).thenReturn("https://example.com/new-rel-domain");
        when(relationshipConceptEditModel.getRange()).thenReturn("https://example.com/new-rel-range");
        when(relationshipConceptEditModel.getSuperRelation()).thenReturn(List.of("https://example.com/new-rel-super"));
        when(relationshipConceptEditModel.getIsInPPDF()).thenReturn(Boolean.TRUE);
        when(relationshipConceptEditModel.getAgendaCode()).thenReturn(null);
        when(relationshipConceptEditModel.getAgendaSystemCode()).thenReturn(null);
        when(relationshipConceptEditModel.getContentType()).thenReturn("novy-obsah-rel");
        when(relationshipConceptEditModel.getAcquisitionMethod()).thenReturn("ziskani-rel");
        when(relationshipConceptEditModel.getSharingMethod()).thenReturn(List.of("sdileni-rel"));
        when(relationshipConceptEditModel.getIsPublic()).thenReturn(null);

        when(relationshipConceptEditModel.getDefiningLegalSource()).thenReturn(null);
        when(relationshipConceptEditModel.getRelatedLegalSource()).thenReturn(null);
        when(relationshipConceptEditModel.getDefiningNonLegalSource()).thenReturn(null);
        when(relationshipConceptEditModel.getRelatedNonLegalSource()).thenReturn(null);
        when(relationshipConceptEditModel.getExactMatch()).thenReturn(null);
        when(relationshipConceptEditModel.getInTezaurus()).thenReturn(null);
        when(relationshipConceptEditModel.getNamespace()).thenReturn(null);

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, relationshipConceptEditModel, model, null);

        assertNotNull(result);
        assertFalse(result.iriChanged);
        assertEquals(conceptIri, result.newConceptIRI);
        assertTrue(result.changesCount > 0);

        Resource updated = model.getResource(conceptIri);
        assertTrue(model.containsResource(updated));

        Resource newDomain = model.getResource("https://example.com/new-rel-domain");
        assertTrue(updated.hasProperty(RDFS.domain, newDomain));
        assertFalse(updated.hasProperty(RDFS.domain, oldDomain));

        Resource newRange = model.getResource("https://example.com/new-rel-range");
        assertTrue(updated.hasProperty(RDFS.range, newRange));
        assertFalse(updated.hasProperty(RDFS.range, oldRange));

        Resource newSuper = model.getResource("https://example.com/new-rel-super");
        assertTrue(updated.hasProperty(RDFS.subPropertyOf, newSuper));
        assertFalse(updated.hasProperty(RDFS.subPropertyOf, oldSuper));

        String ppdfValue = updated.getProperty(ppdfProperty).getObject().asLiteral().getString();
        assertEquals("true", ppdfValue);

        Property contentTypeProp = model.createProperty(OFN_NAMESPACE + TYP_OBSAHU);
        assertTrue(updated.hasProperty(contentTypeProp));
        String contentTypeIri =
                updated.getProperty(contentTypeProp).getObject().asResource().getURI();
        assertTrue(contentTypeIri.startsWith("https://data.dia.gov.cz/zdroj/"));

        Property sharingProp = model.createProperty(OFN_NAMESPACE + ZPUSOB_SDILENI);
        assertTrue(updated.hasProperty(sharingProp));
        String sharingIri =
                updated.getProperty(sharingProp).getObject().asResource().getURI();
        assertTrue(sharingIri.startsWith("https://data.dia.gov.cz/zdroj/"));

        Property acquisitionProp = model.createProperty(OFN_NAMESPACE + ZPUSOB_ZISKANI);
        assertTrue(updated.hasProperty(acquisitionProp));
        String acquisitionIri =
                updated.getProperty(acquisitionProp).getObject().asResource().getURI();
        assertTrue(acquisitionIri.startsWith("https://data.dia.gov.cz/zdroj/"));
    }

    // C3 – IRI rename for VZTAH
    @Test
    void editConcept_ShouldRenameRelationshipConceptIRI_WhenNameChanges() {
        String oldIri = DEFAULT_NS + "old-rel-c3";
        Resource existing = model.createResource(oldIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Old relationship", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + VZTAH));
        Resource domainRes = model.createResource("https://example.com/domain-c3");
        Resource rangeRes = model.createResource("https://example.com/range-c3");
        existing.addProperty(RDFS.domain, domainRes);
        existing.addProperty(RDFS.range, rangeRes);

        stubAllRelationshipFieldsNull(relationshipConceptEditModel);
        NameModel newName = createNameModel("cs", "New relationship c3");
        when(relationshipConceptEditModel.getNameModel()).thenReturn(newName);
        when(relationshipConceptEditModel.getIdentifier()).thenReturn("C3-ID");

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(oldIri, relationshipConceptEditModel, model, null);

        assertTrue(result.iriChanged);
        assertNotEquals(oldIri, result.newConceptIRI);

        Resource newResource = model.getResource(result.newConceptIRI);
        assertTrue(model.containsResource(newResource));

        assertTrue(newResource.hasProperty(RDFS.domain, domainRes));
        assertTrue(newResource.hasProperty(RDFS.range, rangeRes));

        assertFalse(model.containsResource(model.getResource(oldIri)));
    }
}
