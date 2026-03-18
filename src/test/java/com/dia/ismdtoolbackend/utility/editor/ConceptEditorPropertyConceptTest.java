package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.utility.URIGenerator;
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
 * B — PropertyConcept (VLASTNOST) tests
 *   B1 – Domain / range / superproperty and PPDF flag
 *   B2 – Governance content type for properties
 *   B3 – IRI rename for VLASTNOST type
 *   B4 – Domain from non-URI name (generateConceptURI path)
 */
class ConceptEditorPropertyConceptTest extends ConceptEditorTestBase {

    // B1/B2 – Property-specific fields, range/domain, governance and PPDF
    @Test
    void editConcept_ShouldUpdatePropertySpecificFields() {
        String conceptIri = DEFAULT_NS + "property-1";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Property", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + VLASTNOST));

        Resource oldDomain = model.createResource("https://example.com/old-domain");
        existing.addProperty(RDFS.domain, oldDomain);

        Resource oldRange = model.createResource("http://www.w3.org/2001/XMLSchema#integer");
        existing.addProperty(RDFS.range, oldRange);

        Resource oldSuper = model.createResource("https://example.com/old-super");
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

        when(propertyConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.VLASTNOST);
        when(propertyConceptEditModel.getNameModel()).thenReturn(null);
        when(propertyConceptEditModel.getDescriptionModel()).thenReturn(null);
        when(propertyConceptEditModel.getDefinitionModel()).thenReturn(null);
        when(propertyConceptEditModel.getAltNameModel()).thenReturn(null);

        when(propertyConceptEditModel.getDomain()).thenReturn("https://example.com/new-domain");
        when(propertyConceptEditModel.getDataType()).thenReturn("xsd:string");
        when(propertyConceptEditModel.getSuperProperty()).thenReturn(List.of("https://example.com/new-super"));
        when(propertyConceptEditModel.getIsInPPDF()).thenReturn(Boolean.TRUE);
        when(propertyConceptEditModel.getAgendaCode()).thenReturn(null);
        when(propertyConceptEditModel.getAgendaSystemCode()).thenReturn(null);
        when(propertyConceptEditModel.getContentType()).thenReturn("novy-obsah");
        when(propertyConceptEditModel.getAcquisitionMethod()).thenReturn(null);
        when(propertyConceptEditModel.getSharingMethod()).thenReturn(null);
        when(propertyConceptEditModel.getIsPublic()).thenReturn(null);

        when(propertyConceptEditModel.getDefiningLegalSource()).thenReturn(null);
        when(propertyConceptEditModel.getRelatedLegalSource()).thenReturn(null);
        when(propertyConceptEditModel.getDefiningNonLegalSource()).thenReturn(null);
        when(propertyConceptEditModel.getRelatedNonLegalSource()).thenReturn(null);
        when(propertyConceptEditModel.getExactMatch()).thenReturn(null);
        when(propertyConceptEditModel.getInTezaurus()).thenReturn(null);
        when(propertyConceptEditModel.getNamespace()).thenReturn(null);

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, propertyConceptEditModel, model, null);

        assertNotNull(result);
        assertFalse(result.iriChanged);
        assertEquals(conceptIri, result.newConceptIRI);
        assertTrue(result.changesCount > 0);

        Resource updated = model.getResource(conceptIri);
        assertTrue(model.containsResource(updated));

        Resource newDomain = model.getResource("https://example.com/new-domain");
        assertTrue(updated.hasProperty(RDFS.domain, newDomain));
        assertFalse(updated.hasProperty(RDFS.domain, oldDomain));

        Resource range = updated.getProperty(RDFS.range).getObject().asResource();
        assertEquals("http://www.w3.org/2001/XMLSchema#string", range.getURI());

        Resource newSuper = model.getResource("https://example.com/new-super");
        assertTrue(updated.hasProperty(RDFS.subPropertyOf, newSuper));
        assertFalse(updated.hasProperty(RDFS.subPropertyOf, oldSuper));

        String ppdfValue = updated.getProperty(ppdfProperty).getObject().asLiteral().getString();
        assertEquals("true", ppdfValue);

        Property contentTypeProp = model.createProperty(OFN_NAMESPACE + TYP_OBSAHU);
        assertTrue(updated.hasProperty(contentTypeProp));
        String contentTypeIri =
                updated.getProperty(contentTypeProp).getObject().asResource().getURI();
        assertTrue(contentTypeIri.startsWith("https://data.dia.gov.cz/zdroj/"));
    }

    // B3 – IRI rename for VLASTNOST
    @Test
    void editConcept_ShouldRenamePropertyConceptIRI_WhenNameChanges() {
        String oldIri = DEFAULT_NS + "old-prop-b3";
        Resource existing = model.createResource(oldIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Old property", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + VLASTNOST));
        Resource domainRes = model.createResource("https://example.com/domain-b3");
        existing.addProperty(RDFS.domain, domainRes);

        stubAllPropertyFieldsNull(propertyConceptEditModel);
        NameModel newName = createNameModel("cs", "New property b3");
        when(propertyConceptEditModel.getNameModel()).thenReturn(newName);
        when(propertyConceptEditModel.getIdentifier()).thenReturn("B3-ID");

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(oldIri, propertyConceptEditModel, model, null);

        assertTrue(result.iriChanged);
        assertNotEquals(oldIri, result.newConceptIRI);

        Resource newResource = model.getResource(result.newConceptIRI);
        assertTrue(model.containsResource(newResource));

        assertTrue(newResource.hasProperty(RDFS.domain, domainRes));

        assertFalse(model.containsResource(model.getResource(oldIri)));
    }

    // B4 – Domain from non-URI name
    @Test
    void editConcept_ShouldGenerateConceptURI_WhenDomainIsNotURI() {
        String conceptIri = DEFAULT_NS + "prop-b4";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Property b4", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + VLASTNOST));

        stubAllPropertyFieldsNull(propertyConceptEditModel);
        when(propertyConceptEditModel.getDomain()).thenReturn("Osoba");

        conceptEditor.editConcept(conceptIri, propertyConceptEditModel, model, null);

        Resource updated = model.getResource(conceptIri);
        assertTrue(updated.hasProperty(RDFS.domain));
        String domainUri = updated.getProperty(RDFS.domain).getObject().asResource().getURI();
        assertNotEquals("Osoba", domainUri);
        URIGenerator uriGen = new URIGenerator();
        String expectedUri = uriGen.generateConceptURI("Osoba", null);
        assertEquals(expectedUri, domainUri);
    }
}
