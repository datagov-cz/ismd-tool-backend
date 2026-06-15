package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.concept.AltNameModel;
import com.dia.ismdtoolbackend.models.concept.DefinitionModel;
import com.dia.ismdtoolbackend.models.concept.DigitalObjectModel;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.utility.URIGenerator;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static com.dia.constants.VocabularyConstants.*;
import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;

/**
 * A — ClassConcept (TRIDA) tests
 *   A1  – IRI rename when name changes
 *   A2  – Class-specific fields (type, agenda, governance, broader hierarchy)
 *   A3  – Legal sources (defining / related)
 *   A4  – Non-legal sources (defining / related)
 *   A5  – Common text fields (name / description / definition / altLabel)
 *   A5b – Clear common text fields
 *   A6  – Privacy provision (set / clear)
 *   A7  – Legal and non-legal sources removal when cleared
 *   A8  – IRI rename updates object references (concept as object of rdfs:domain)
 *   A9  – IRI rename + simultaneous field updates (predicate exclusion correctness)
 *   A10 – Exact match update + clear
 *   A10c – Sharing method list with multiple values
 *   A11a-d – Data classification (4 tests)
 *   A12 – inTezaurus boolean update
 *   A8b – Classification removal during rename
 */
class ConceptEditorClassConceptTest extends ConceptEditorTestBase {

    // A1 – IRI rename when name changes
    @Test
    void editConcept_ShouldRenameConceptIRI_WhenNameChanges() {
        String oldIri = "https://slovnik.gov.cz/pojem/old-class";
        Resource existing = model.createResource(oldIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Old name", "cs"));
        existing.addProperty(RDF.type, SKOS.Concept);

        NameModel newName = createNameModel("cs", "New name");

        when(classConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
        when(classConceptEditModel.getNameModel()).thenReturn(newName);
        when(classConceptEditModel.getIdentifier()).thenReturn("ID-1");
        when(classConceptEditModel.getIsPublic()).thenReturn(null);
        when(classConceptEditModel.getIsInPPDF()).thenReturn(null);

        ConceptEditor.EditResult result = conceptEditor.editConcept(oldIri, classConceptEditModel, model, null);

        assertNotNull(result);
        assertTrue(result.iriChanged);
        assertNotEquals(oldIri, result.newConceptIRI);

        Resource oldResource = model.getResource(oldIri);
        assertFalse(model.containsResource(oldResource));

        Resource newResource = model.getResource(result.newConceptIRI);
        assertTrue(model.containsResource(newResource));
        assertNotNull(newResource.getProperty(SKOS.prefLabel));
        assertEquals("New name", newResource.getProperty(SKOS.prefLabel).getObject().asLiteral().getString());
        assertTrue(result.changesCount > 0);
    }

    // A2 – Class-specific fields (type, agenda, governance, broader hierarchy)
    @Test
    void editConcept_ShouldUpdateClassSpecificFields() {
        String conceptIri = DEFAULT_NS + "class-1";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TOP));

        Property agendaProperty = model.createProperty(DEFAULT_NS + AGENDOVY_104 + AGENDA_LONG);
        existing.addProperty(agendaProperty, model.createLiteral("old-agenda"));

        Property sharingProperty = model.createProperty(OFN_NAMESPACE + ZPUSOB_SDILENI);
        existing.addProperty(
                sharingProperty,
                model.createResource(
                        "https://data.dia.gov.cz/zdroj/číselníky/způsoby-sdílení-údajů/položky/old"
                )
        );

        Property hierarchyProperty = model.createProperty(DEFAULT_NS + "nadřazená-třída");
        Resource oldBroader = model.createResource("https://example.com/old-broader");
        existing.addProperty(RDFS.subClassOf, oldBroader);
        existing.addProperty(hierarchyProperty, oldBroader);

        when(classConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
        when(classConceptEditModel.getNameModel()).thenReturn(null);
        when(classConceptEditModel.getDescriptionModel()).thenReturn(null);
        when(classConceptEditModel.getDefinitionModel()).thenReturn(null);
        when(classConceptEditModel.getAltNameModel()).thenReturn(null);

        when(classConceptEditModel.getType()).thenReturn("subjekt");
        when(classConceptEditModel.getAgendaCode()).thenReturn("");
        when(classConceptEditModel.getAgendaSystemCode()).thenReturn(null);
        when(classConceptEditModel.getContentType()).thenReturn("obsah");
        when(classConceptEditModel.getAcquisitionMethod()).thenReturn("ziskani");
        when(classConceptEditModel.getSharingMethod()).thenReturn(List.of("sdileni"));
        when(classConceptEditModel.getIsPublic()).thenReturn(null);
        when(classConceptEditModel.getIsInPPDF()).thenReturn(null);
        when(classConceptEditModel.getBroaderConcept()).thenReturn(List.of("https://example.com/new-broader"));

        when(classConceptEditModel.getDefiningLegalSource()).thenReturn(null);
        when(classConceptEditModel.getRelatedLegalSource()).thenReturn(null);
        when(classConceptEditModel.getDefiningNonLegalSource()).thenReturn(null);
        when(classConceptEditModel.getRelatedNonLegalSource()).thenReturn(null);
        when(classConceptEditModel.getExactMatch()).thenReturn(null);
        when(classConceptEditModel.getInTezaurus()).thenReturn(null);

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        assertNotNull(result);
        assertFalse(result.iriChanged);
        assertEquals(conceptIri, result.newConceptIRI);
        assertTrue(result.changesCount > 0);

        Resource updated = model.getResource(conceptIri);
        assertTrue(model.containsResource(updated));

        Resource tspType = model.getResource(OFN_NAMESPACE + TSP);
        Resource topType = model.getResource(OFN_NAMESPACE + TOP);

        assertTrue(updated.hasProperty(RDF.type, tspType));
        assertFalse(updated.hasProperty(RDF.type, topType));

        assertFalse(updated.hasProperty(agendaProperty));

        Property sharingProp = model.createProperty(OFN_NAMESPACE + ZPUSOB_SDILENI);
        assertTrue(updated.hasProperty(sharingProp));
        String sharingIri =
                updated.getProperty(sharingProp).getObject().asResource().getURI();
        assertTrue(sharingIri.startsWith("https://data.dia.gov.cz/zdroj/"));

        Resource newBroader = model.getResource("https://example.com/new-broader");
        assertTrue(updated.hasProperty(RDFS.subClassOf, newBroader));
        assertTrue(updated.hasProperty(hierarchyProperty, newBroader));
        assertFalse(updated.hasProperty(RDFS.subClassOf, oldBroader));
        assertFalse(updated.hasProperty(hierarchyProperty, oldBroader));
    }

    // A3 – Legal sources (defining / related)
    @Test
    void editConcept_ShouldUpdateLegalSources() {
        String conceptIri = DEFAULT_NS + "class-legal";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class legal", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        Property definingProp = model.createProperty(OFN_NAMESPACE + DEFINUJICI_USTANOVENI);
        Property relatedProp = model.createProperty(OFN_NAMESPACE + SOUVISEJICI_USTANOVENI);

        Resource oldDef = model.createResource("https://old.example.com/eli/cz/act/2000/1");
        Resource oldRel = model.createResource("https://old.example.com/eli/cz/act/2000/2");
        existing.addProperty(definingProp, oldDef);
        existing.addProperty(relatedProp, oldRel);

        when(classConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
        when(classConceptEditModel.getNameModel()).thenReturn(null);
        when(classConceptEditModel.getDescriptionModel()).thenReturn(null);
        when(classConceptEditModel.getDefinitionModel()).thenReturn(null);
        when(classConceptEditModel.getAltNameModel()).thenReturn(null);

        when(classConceptEditModel.getType()).thenReturn(null);
        when(classConceptEditModel.getAgendaCode()).thenReturn(null);
        when(classConceptEditModel.getAgendaSystemCode()).thenReturn(null);
        when(classConceptEditModel.getContentType()).thenReturn(null);
        when(classConceptEditModel.getAcquisitionMethod()).thenReturn(null);
        when(classConceptEditModel.getSharingMethod()).thenReturn(null);
        when(classConceptEditModel.getIsPublic()).thenReturn(null);
        when(classConceptEditModel.getIsInPPDF()).thenReturn(null);
        when(classConceptEditModel.getBroaderConcept()).thenReturn(null);

        when(classConceptEditModel.getDefiningLegalSource())
                .thenReturn(List.of("https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2021/12"));
        when(classConceptEditModel.getRelatedLegalSource())
                .thenReturn(List.of("https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2022/100"));

        when(classConceptEditModel.getDefiningNonLegalSource()).thenReturn(null);
        when(classConceptEditModel.getRelatedNonLegalSource()).thenReturn(null);
        when(classConceptEditModel.getExactMatch()).thenReturn(null);
        when(classConceptEditModel.getInTezaurus()).thenReturn(null);

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        assertNotNull(result);
        assertFalse(result.iriChanged);
        assertEquals(conceptIri, result.newConceptIRI);
        assertTrue(result.changesCount > 0);

        Resource updated = model.getResource(conceptIri);
        assertTrue(model.containsResource(updated));

        assertFalse(updated.hasProperty(definingProp, oldDef));
        assertFalse(updated.hasProperty(relatedProp, oldRel));

        String definingIri = updated.listProperties(definingProp)
                .nextStatement()
                .getObject()
                .asResource()
                .getURI();
        assertTrue(definingIri.startsWith("https://opendata.eselpoint.gov.cz/esel-esb/"));
        assertTrue(definingIri.contains("2021/12"));

        String relatedIri = updated.listProperties(relatedProp)
                .nextStatement()
                .getObject()
                .asResource()
                .getURI();
        assertTrue(relatedIri.startsWith("https://opendata.eselpoint.gov.cz/esel-esb/"));
        assertTrue(relatedIri.contains("2022/100"));
    }

    // A4 – Non-legal sources (defining / related)
    @Test
    void editConcept_ShouldUpdateNonLegalSources() {
        String conceptIri = DEFAULT_NS + "class-nonlegal";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class nonlegal", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        Property definingProp = model.createProperty(DEFAULT_NS + DEFINUJICI_NELEGISLATIVNI_ZDROJ);
        Property relatedProp = model.createProperty(DEFAULT_NS + SOUVISEJICI_NELEGISLATIVNI_ZDROJ);
        Property schemaUrlProp = model.createProperty(SCHEMA_URL);
        Property dctTitleProp = model.createProperty(DCT_NS + "title");
        Property dctDescProp = model.createProperty(DCT_NS + "description");

        when(classConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
        when(classConceptEditModel.getNameModel()).thenReturn(null);
        when(classConceptEditModel.getDescriptionModel()).thenReturn(null);
        when(classConceptEditModel.getDefinitionModel()).thenReturn(null);
        when(classConceptEditModel.getAltNameModel()).thenReturn(null);

        when(classConceptEditModel.getType()).thenReturn(null);
        when(classConceptEditModel.getAgendaCode()).thenReturn(null);
        when(classConceptEditModel.getAgendaSystemCode()).thenReturn(null);
        when(classConceptEditModel.getContentType()).thenReturn(null);
        when(classConceptEditModel.getAcquisitionMethod()).thenReturn(null);
        when(classConceptEditModel.getSharingMethod()).thenReturn(null);
        when(classConceptEditModel.getIsPublic()).thenReturn(null);
        when(classConceptEditModel.getIsInPPDF()).thenReturn(null);
        when(classConceptEditModel.getBroaderConcept()).thenReturn(null);

        when(classConceptEditModel.getDefiningLegalSource()).thenReturn(null);
        when(classConceptEditModel.getRelatedLegalSource()).thenReturn(null);
        when(classConceptEditModel.getDefiningNonLegalSource())
                .thenReturn(List.of(new DigitalObjectModel("Doc 1", "Popis 1", "https://example.com/doc1")));
        when(classConceptEditModel.getRelatedNonLegalSource())
                .thenReturn(List.of(new DigitalObjectModel("Doc 2", "Popis 2", "https://example.com/doc2")));
        when(classConceptEditModel.getExactMatch()).thenReturn(null);
        when(classConceptEditModel.getInTezaurus()).thenReturn(null);

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        assertNotNull(result);
        assertFalse(result.iriChanged);
        assertEquals(conceptIri, result.newConceptIRI);
        assertTrue(result.changesCount > 0);

        Resource updated = model.getResource(conceptIri);
        assertTrue(model.containsResource(updated));

        Resource definingDoc = updated.listProperties(definingProp)
                .nextStatement()
                .getObject()
                .asResource();
        assertTrue(definingDoc.hasProperty(schemaUrlProp, model.createResource("https://example.com/doc1")));
        assertTrue(definingDoc.hasProperty(dctTitleProp, model.createLiteral("Doc 1", DEFAULT_LANG)));
        assertTrue(definingDoc.hasProperty(dctDescProp, model.createLiteral("Popis 1", DEFAULT_LANG)));

        Resource relatedDoc = updated.listProperties(relatedProp)
                .nextStatement()
                .getObject()
                .asResource();
        assertTrue(relatedDoc.hasProperty(schemaUrlProp, model.createResource("https://example.com/doc2")));
        assertTrue(relatedDoc.hasProperty(dctTitleProp, model.createLiteral("Doc 2", DEFAULT_LANG)));
        assertTrue(relatedDoc.hasProperty(dctDescProp, model.createLiteral("Popis 2", DEFAULT_LANG)));
    }

    // A5 – Common text fields update (name / description / definition / altLabel)
    @Test
    void editConcept_ShouldUpdateCommonTextFields() {
        String conceptIri = DEFAULT_NS + "class-common-text";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Old name", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        Property descProperty = model.createProperty("http://purl.org/dc/terms/description");
        existing.addProperty(descProperty, model.createLiteral("Old description", "cs"));
        existing.addProperty(SKOS.definition, model.createLiteral("Old definition", "cs"));
        existing.addProperty(SKOS.altLabel, model.createLiteral("Old alt", "cs"));

        NameModel newName = createNameModel("cs", "New name");
        DescriptionModel newDesc = createDescriptionModel("cs", "New description");
        DefinitionModel newDef = createDefinitionModel("cs", "New definition");
        AltNameModel newAlt = createAltNameModel("cs", "New alt");

        when(classConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);

        when(classConceptEditModel.getNameModel()).thenReturn(newName);
        when(classConceptEditModel.getDescriptionModel()).thenReturn(newDesc);
        when(classConceptEditModel.getDefinitionModel()).thenReturn(newDef);
        when(classConceptEditModel.getAltNameModel()).thenReturn(newAlt);

        when(classConceptEditModel.getDefiningLegalSource()).thenReturn(null);
        when(classConceptEditModel.getRelatedLegalSource()).thenReturn(null);
        when(classConceptEditModel.getDefiningNonLegalSource()).thenReturn(null);
        when(classConceptEditModel.getRelatedNonLegalSource()).thenReturn(null);
        when(classConceptEditModel.getExactMatch()).thenReturn(null);
        when(classConceptEditModel.getInTezaurus()).thenReturn(null);
        when(classConceptEditModel.getIsPublic()).thenReturn(null);
        when(classConceptEditModel.getIsInPPDF()).thenReturn(null);

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        assertNotNull(result);
        assertTrue(result.iriChanged);
        assertNotEquals(conceptIri, result.newConceptIRI);

        Resource updated = model.getResource(result.newConceptIRI);

        assertEquals("New description",
                updated.getProperty(descProperty).getObject().asLiteral().getString());
        assertEquals("New definition",
                updated.getProperty(SKOS.definition).getObject().asLiteral().getString());
        assertEquals("New alt",
                updated.getProperty(SKOS.altLabel).getObject().asLiteral().getString());
    }

    // A5b – Common text fields clear when values are empty
    @Test
    void editConcept_ShouldClearCommonTextFieldsWhenEmpty() {
        String conceptIri = DEFAULT_NS + "class-common-clear";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Name", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        Property descProperty = model.createProperty("http://purl.org/dc/terms/description");
        existing.addProperty(descProperty, model.createLiteral("Description", "cs"));
        existing.addProperty(SKOS.definition, model.createLiteral("Definition", "cs"));
        existing.addProperty(SKOS.altLabel, model.createLiteral("Alt", "cs"));

        DescriptionModel emptyDesc = createDescriptionModel("cs", "");
        DefinitionModel emptyDef = createDefinitionModel("cs", " ");

        when(classConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);

        when(classConceptEditModel.getNameModel()).thenReturn(null);
        when(classConceptEditModel.getDescriptionModel()).thenReturn(emptyDesc);
        when(classConceptEditModel.getDefinitionModel()).thenReturn(emptyDef);
        when(classConceptEditModel.getAltNameModel()).thenReturn(null);

        when(classConceptEditModel.getDefiningLegalSource()).thenReturn(null);
        when(classConceptEditModel.getRelatedLegalSource()).thenReturn(null);
        when(classConceptEditModel.getDefiningNonLegalSource()).thenReturn(null);
        when(classConceptEditModel.getRelatedNonLegalSource()).thenReturn(null);
        when(classConceptEditModel.getExactMatch()).thenReturn(null);
        when(classConceptEditModel.getInTezaurus()).thenReturn(null);
        when(classConceptEditModel.getIsPublic()).thenReturn(null);
        when(classConceptEditModel.getIsInPPDF()).thenReturn(null);

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        Resource updated = model.getResource(conceptIri);
        assertNotNull(result);
        assertFalse(result.iriChanged);
        assertEquals(conceptIri, result.newConceptIRI);

        assertFalse(updated.hasProperty(descProperty));
        assertFalse(updated.hasProperty(SKOS.definition));
        assertTrue(updated.hasProperty(SKOS.altLabel));
    }

    // A6 – Privacy provision updated from ELI URL
    @Test
    void editConcept_ShouldUpdatePrivacyProvisionFromEli() {
        String conceptIri = DEFAULT_NS + "class-privacy";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class privacy", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        Property provisionProperty = model.createProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST);

        when(classConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
        when(classConceptEditModel.getNameModel()).thenReturn(null);
        when(classConceptEditModel.getDescriptionModel()).thenReturn(null);
        when(classConceptEditModel.getDefinitionModel()).thenReturn(null);
        when(classConceptEditModel.getAltNameModel()).thenReturn(null);

        when(classConceptEditModel.getPrivacyProvisions())
                .thenReturn(List.of("https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2023/50"));
        when(classConceptEditModel.getIsPublic()).thenReturn(null);
        when(classConceptEditModel.getIsInPPDF()).thenReturn(null);
        when(classConceptEditModel.getType()).thenReturn(null);
        when(classConceptEditModel.getAgendaCode()).thenReturn(null);
        when(classConceptEditModel.getAgendaSystemCode()).thenReturn(null);
        when(classConceptEditModel.getContentType()).thenReturn(null);
        when(classConceptEditModel.getAcquisitionMethod()).thenReturn(null);
        when(classConceptEditModel.getSharingMethod()).thenReturn(null);
        when(classConceptEditModel.getBroaderConcept()).thenReturn(null);

        when(classConceptEditModel.getDefiningLegalSource()).thenReturn(null);
        when(classConceptEditModel.getRelatedLegalSource()).thenReturn(null);
        when(classConceptEditModel.getDefiningNonLegalSource()).thenReturn(null);
        when(classConceptEditModel.getRelatedNonLegalSource()).thenReturn(null);
        when(classConceptEditModel.getExactMatch()).thenReturn(null);
        when(classConceptEditModel.getInTezaurus()).thenReturn(null);

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        Resource updated = model.getResource(conceptIri);
        assertNotNull(result);
        assertFalse(result.iriChanged);
        assertEquals(conceptIri, result.newConceptIRI);

        String provisionIri = updated.listProperties(provisionProperty)
                .nextStatement()
                .getObject()
                .asResource()
                .getURI();
        assertTrue(provisionIri.startsWith("https://opendata.eselpoint.gov.cz/esel-esb/"));
        assertTrue(provisionIri.contains("2023/50"));
    }

    // A6 – Privacy provision cleared when value is blank
    @Test
    void editConcept_ShouldClearPrivacyProvisionWhenEmpty() {
        String conceptIri = DEFAULT_NS + "class-privacy-clear";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class privacy clear", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        Property provisionProperty = model.createProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST);
        Resource oldProvision = model.createResource("https://opendata.eselpoint.gov.cz/esel-esb/2020/10");
        existing.addProperty(provisionProperty, oldProvision);

        when(classConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
        when(classConceptEditModel.getNameModel()).thenReturn(null);
        when(classConceptEditModel.getDescriptionModel()).thenReturn(null);
        when(classConceptEditModel.getDefinitionModel()).thenReturn(null);
        when(classConceptEditModel.getAltNameModel()).thenReturn(null);

        when(classConceptEditModel.getPrivacyProvisions()).thenReturn(List.of(" "));
        when(classConceptEditModel.getIsPublic()).thenReturn(null);
        when(classConceptEditModel.getIsInPPDF()).thenReturn(null);
        when(classConceptEditModel.getType()).thenReturn(null);
        when(classConceptEditModel.getAgendaCode()).thenReturn(null);
        when(classConceptEditModel.getAgendaSystemCode()).thenReturn(null);
        when(classConceptEditModel.getContentType()).thenReturn(null);
        when(classConceptEditModel.getAcquisitionMethod()).thenReturn(null);
        when(classConceptEditModel.getSharingMethod()).thenReturn(null);
        when(classConceptEditModel.getBroaderConcept()).thenReturn(null);

        when(classConceptEditModel.getDefiningLegalSource()).thenReturn(null);
        when(classConceptEditModel.getRelatedLegalSource()).thenReturn(null);
        when(classConceptEditModel.getDefiningNonLegalSource()).thenReturn(null);
        when(classConceptEditModel.getRelatedNonLegalSource()).thenReturn(null);
        when(classConceptEditModel.getExactMatch()).thenReturn(null);
        when(classConceptEditModel.getInTezaurus()).thenReturn(null);

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        Resource updated = model.getResource(conceptIri);
        assertNotNull(result);
        assertFalse(result.iriChanged);
        assertEquals(conceptIri, result.newConceptIRI);

        assertFalse(updated.hasProperty(provisionProperty));
    }

    // A7 – Legal sources removed when lists are empty
    @Test
    void editConcept_ShouldRemoveLegalSourcesWhenListEmpty() {
        String conceptIri = DEFAULT_NS + "class-legal-clear";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class legal clear", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        Property definingProp = model.createProperty(OFN_NAMESPACE + DEFINUJICI_USTANOVENI);
        Property relatedProp = model.createProperty(OFN_NAMESPACE + SOUVISEJICI_USTANOVENI);

        existing.addProperty(definingProp, model.createResource("https://opendata.eselpoint.gov.cz/esel-esb/2020/1"));
        existing.addProperty(relatedProp, model.createResource("https://opendata.eselpoint.gov.cz/esel-esb/2020/2"));

        when(classConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
        when(classConceptEditModel.getNameModel()).thenReturn(null);
        when(classConceptEditModel.getDescriptionModel()).thenReturn(null);
        when(classConceptEditModel.getDefinitionModel()).thenReturn(null);
        when(classConceptEditModel.getAltNameModel()).thenReturn(null);

        when(classConceptEditModel.getDefiningLegalSource()).thenReturn(Collections.emptyList());
        when(classConceptEditModel.getRelatedLegalSource()).thenReturn(Collections.emptyList());

        when(classConceptEditModel.getDefiningNonLegalSource()).thenReturn(null);
        when(classConceptEditModel.getRelatedNonLegalSource()).thenReturn(null);
        when(classConceptEditModel.getType()).thenReturn(null);
        when(classConceptEditModel.getAgendaCode()).thenReturn(null);
        when(classConceptEditModel.getAgendaSystemCode()).thenReturn(null);
        when(classConceptEditModel.getContentType()).thenReturn(null);
        when(classConceptEditModel.getAcquisitionMethod()).thenReturn(null);
        when(classConceptEditModel.getSharingMethod()).thenReturn(null);
        when(classConceptEditModel.getIsPublic()).thenReturn(null);
        when(classConceptEditModel.getIsInPPDF()).thenReturn(null);
        when(classConceptEditModel.getBroaderConcept()).thenReturn(null);
        when(classConceptEditModel.getExactMatch()).thenReturn(null);
        when(classConceptEditModel.getInTezaurus()).thenReturn(null);

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        Resource updated = model.getResource(conceptIri);
        assertNotNull(result);
        assertFalse(result.iriChanged);
        assertEquals(conceptIri, result.newConceptIRI);

        assertFalse(updated.hasProperty(definingProp));
        assertFalse(updated.hasProperty(relatedProp));
    }

    // A7 – Non-legal sources with blank/invalid url now REJECT the whole edit (HTTP 400),
    // listing every offending field, and leave the model untouched (no partial write).
    @Test
    void editConcept_ShouldRejectNonLegalSourcesWithInvalidUrl() {
        String conceptIri = DEFAULT_NS + "class-nonlegal-clear";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class nonlegal clear", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        Property definingProp = model.createProperty(DEFAULT_NS + DEFINUJICI_NELEGISLATIVNI_ZDROJ);
        Property relatedProp = model.createProperty(DEFAULT_NS + SOUVISEJICI_NELEGISLATIVNI_ZDROJ);

        existing.addProperty(definingProp, model.createResource(DEFAULT_NS + "digitální-dokument-1"));
        existing.addProperty(relatedProp, model.createResource(DEFAULT_NS + "digitální-dokument-2"));

        long sizeBefore = model.size();

        // Only the fields the pre-flight validator reads need stubbing — validation
        // throws before the type dispatch, so e.g. getConceptTypeEnum is never reached.
        lenient().when(classConceptEditModel.getDefiningNonLegalSource())
                .thenReturn(List.of(new DigitalObjectModel("Doc with no url", "Popis", "")));
        lenient().when(classConceptEditModel.getRelatedNonLegalSource())
                .thenReturn(List.of(new DigitalObjectModel("Doc with invalid url", "Popis", "not a url")));
        lenient().when(classConceptEditModel.getDefiningLegalSource()).thenReturn(null);
        lenient().when(classConceptEditModel.getRelatedLegalSource()).thenReturn(null);
        lenient().when(classConceptEditModel.getAgendaCode()).thenReturn(null);
        lenient().when(classConceptEditModel.getAgendaSystemCode()).thenReturn(null);
        lenient().when(classConceptEditModel.getExactMatch()).thenReturn(null);
        lenient().when(classConceptEditModel.getPrivacyProvisions()).thenReturn(null);

        ConceptValidationException ex = assertThrows(ConceptValidationException.class, () ->
                conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null));

        // every offending field is named in the message
        assertTrue(ex.getMessage().contains("definingNonLegalSource"), ex.getMessage());
        assertTrue(ex.getMessage().contains("relatedNonLegalSource"), ex.getMessage());

        // no mutation occurred — the existing non-legal links are still present
        Resource updated = model.getResource(conceptIri);
        assertTrue(updated.hasProperty(definingProp));
        assertTrue(updated.hasProperty(relatedProp));
        assertEquals(sizeBefore, model.size(), "rejected edit must not change the model");
    }

    // A8 – IRI rename updates object references
    @Test
    void editConcept_ShouldRenameObjectReferences_WhenIRIChanges() {
        String oldIri = DEFAULT_NS + "old-class-a8";
        Resource existing = model.createResource(oldIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Old name", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        Resource otherResource = model.createResource(DEFAULT_NS + "other-property");
        otherResource.addProperty(RDFS.domain, existing);

        stubAllClassFieldsNull(classConceptEditModel);
        NameModel newName = createNameModel("cs", "New name a8");
        when(classConceptEditModel.getNameModel()).thenReturn(newName);
        when(classConceptEditModel.getIdentifier()).thenReturn("A8-ID");

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(oldIri, classConceptEditModel, model, null);

        assertTrue(result.iriChanged);
        assertNotEquals(oldIri, result.newConceptIRI);

        Resource oldResource = model.getResource(oldIri);
        assertFalse(model.containsResource(oldResource));

        Resource updatedOther = model.getResource(DEFAULT_NS + "other-property");
        assertTrue(updatedOther.hasProperty(RDFS.domain, model.getResource(result.newConceptIRI)));
        assertFalse(updatedOther.hasProperty(RDFS.domain, model.getResource(oldIri)));
    }

    // A9 – IRI rename + predicate exclusion
    @Test
    void editConcept_ShouldExcludeEditedPredicatesDuringRename() {
        String oldIri = DEFAULT_NS + "old-class-a9";
        Resource existing = model.createResource(oldIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Old name", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TOP));
        Resource oldBroader = model.createResource("https://example.com/old-broader-a9");
        existing.addProperty(RDFS.subClassOf, oldBroader);

        stubAllClassFieldsNull(classConceptEditModel);
        NameModel newName = createNameModel("cs", "New name a9");
        when(classConceptEditModel.getNameModel()).thenReturn(newName);
        when(classConceptEditModel.getIdentifier()).thenReturn("A9-ID");
        when(classConceptEditModel.getType()).thenReturn("subjekt");
        when(classConceptEditModel.getBroaderConcept()).thenReturn(List.of("https://example.com/new-broader-a9"));

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(oldIri, classConceptEditModel, model, null);

        assertTrue(result.iriChanged);
        Resource newResource = model.getResource(result.newConceptIRI);

        Resource tspType = model.getResource(OFN_NAMESPACE + TSP);
        Resource topType = model.getResource(OFN_NAMESPACE + TOP);
        assertTrue(newResource.hasProperty(RDF.type, tspType));
        assertFalse(newResource.hasProperty(RDF.type, topType));

        Resource newBroader = model.getResource("https://example.com/new-broader-a9");
        assertTrue(newResource.hasProperty(RDFS.subClassOf, newBroader));
        assertFalse(newResource.hasProperty(RDFS.subClassOf, oldBroader));

        assertFalse(model.containsResource(model.getResource(oldIri)));
    }

    // A10 – Exact match update
    @Test
    void editConcept_ShouldUpdateExactMatchList() {
        String conceptIri = DEFAULT_NS + "class-a10";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class a10", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        Property exactMatchProp = model.createProperty("http://www.w3.org/2004/02/skos/core#exactMatch");
        existing.addProperty(exactMatchProp, model.createResource("https://example.com/old-match"));

        stubAllClassFieldsNull(classConceptEditModel);
        when(classConceptEditModel.getExactMatch())
                .thenReturn(List.of("https://example.com/new-uri-1", " ", "https://example.com/new-uri-2"));

        conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        Resource updated = model.getResource(conceptIri);
        assertFalse(updated.hasProperty(exactMatchProp, model.createResource("https://example.com/old-match")));
        assertTrue(updated.hasProperty(exactMatchProp, model.createResource("https://example.com/new-uri-1")));
        assertTrue(updated.hasProperty(exactMatchProp, model.createResource("https://example.com/new-uri-2")));
    }

    // A10b – Exact match clear
    @Test
    void editConcept_ShouldClearExactMatch_WhenListIsEmpty() {
        String conceptIri = DEFAULT_NS + "class-a10b";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class a10b", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        Property exactMatchProp = model.createProperty("http://www.w3.org/2004/02/skos/core#exactMatch");
        existing.addProperty(exactMatchProp, model.createResource("https://example.com/old-match"));

        stubAllClassFieldsNull(classConceptEditModel);
        when(classConceptEditModel.getExactMatch()).thenReturn(Collections.emptyList());

        conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        Resource updated = model.getResource(conceptIri);
        assertFalse(updated.hasProperty(exactMatchProp));
    }

    // A10c – Sharing method list with multiple values
    @Test
    void editConcept_ShouldUpdateSharingMethodList_WithMultipleValues() {
        String conceptIri = DEFAULT_NS + "class-a10c";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class a10c", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        stubAllClassFieldsNull(classConceptEditModel);
        when(classConceptEditModel.getSharingMethod()).thenReturn(List.of("value-1", "value-2"));

        conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        Resource updated = model.getResource(conceptIri);
        Property sharingProp = model.createProperty(OFN_NAMESPACE + ZPUSOB_SDILENI);

        long count = updated.listProperties(sharingProp).toList().size();
        assertEquals(2, count);

        updated.listProperties(sharingProp).forEachRemaining(stmt -> {
            assertTrue(stmt.getObject().isResource());
            assertTrue(stmt.getObject().asResource().getURI().startsWith("https://data.dia.gov.cz/zdroj/"));
        });
    }

    // A11a – Data classification: public, no provisions
    @Test
    void editConcept_ShouldClassifyAsVerejnyUdaj_WhenIsPublicTrueAndNoProvisions() {
        String conceptIri = DEFAULT_NS + "class-a11a";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class a11a", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        stubAllClassFieldsNull(classConceptEditModel);
        when(classConceptEditModel.getIsPublic()).thenReturn(Boolean.TRUE);
        when(classConceptEditModel.getPrivacyProvisions()).thenReturn(null);

        conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        Resource updated = model.getResource(conceptIri);
        Resource verejny = model.getResource(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ);
        Resource neverejny = model.getResource(OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ);
        assertTrue(updated.hasProperty(RDF.type, verejny));
        assertFalse(updated.hasProperty(RDF.type, neverejny));
    }

    // A11b – Data classification: private with provisions
    @Test
    void editConcept_ShouldClassifyAsNeverejnyUdaj_WhenIsPublicFalseAndHasProvisions() {
        String conceptIri = DEFAULT_NS + "class-a11b";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class a11b", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        stubAllClassFieldsNull(classConceptEditModel);
        when(classConceptEditModel.getIsPublic()).thenReturn(Boolean.FALSE);
        when(classConceptEditModel.getPrivacyProvisions())
                .thenReturn(List.of("https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2023/50"));

        conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        Resource updated = model.getResource(conceptIri);
        Resource verejny = model.getResource(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ);
        Resource neverejny = model.getResource(OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ);
        assertTrue(updated.hasProperty(RDF.type, neverejny));
        assertFalse(updated.hasProperty(RDF.type, verejny));
    }

    // A11c – Data classification: private, no provisions = no classification
    @Test
    void editConcept_ShouldNotClassify_WhenIsPublicFalseAndNoProvisions() {
        String conceptIri = DEFAULT_NS + "class-a11c";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class a11c", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));
        Resource verejny = model.getResource(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ);
        existing.addProperty(RDF.type, verejny);

        stubAllClassFieldsNull(classConceptEditModel);
        when(classConceptEditModel.getIsPublic()).thenReturn(Boolean.FALSE);
        when(classConceptEditModel.getPrivacyProvisions()).thenReturn(null);

        conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        Resource updated = model.getResource(conceptIri);
        Resource neverejny = model.getResource(OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ);
        assertFalse(updated.hasProperty(RDF.type, verejny));
        assertFalse(updated.hasProperty(RDF.type, neverejny));
    }

    // A11d – Data classification: public but has provisions
    @Test
    void editConcept_ShouldNotAddVerejnyUdaj_WhenIsPublicTrueButHasProvisions() {
        String conceptIri = DEFAULT_NS + "class-a11d";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class a11d", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        stubAllClassFieldsNull(classConceptEditModel);
        when(classConceptEditModel.getIsPublic()).thenReturn(Boolean.TRUE);
        when(classConceptEditModel.getPrivacyProvisions())
                .thenReturn(List.of("https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2023/50"));

        conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        Resource updated = model.getResource(conceptIri);
        Resource verejny = model.getResource(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ);
        assertFalse(updated.hasProperty(RDF.type, verejny));
    }

    // A12 – inTezaurus boolean update
    @Test
    void editConcept_ShouldUpdateInTezaurusFlag() {
        String conceptIri = DEFAULT_NS + "class-a12";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class a12", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        stubAllClassFieldsNull(classConceptEditModel);
        when(classConceptEditModel.getInTezaurus()).thenReturn(Boolean.TRUE);

        conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        Resource updated = model.getResource(conceptIri);
        Property inTezaurusProp = model.createProperty(DEFAULT_NS + "inTezaurus");
        assertTrue(updated.hasProperty(inTezaurusProp));
        assertEquals("true", updated.getProperty(inTezaurusProp).getObject().asLiteral().getString());
    }

    // A8b – Classification removal during rename with type change
    @Test
    void editConcept_ShouldRemoveOldClassification_WhenIRIRenamedWithTypeChangeAndIsPublicFalse() {
        String oldIri = DEFAULT_NS + "class-a8b";
        Resource existing = model.createResource(oldIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Old name a8b", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TOP));
        Resource verejny = model.getResource(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ);
        existing.addProperty(RDF.type, verejny);

        stubAllClassFieldsNull(classConceptEditModel);
        NameModel newName = createNameModel("cs", "New name a8b");
        when(classConceptEditModel.getNameModel()).thenReturn(newName);
        when(classConceptEditModel.getIdentifier()).thenReturn("A8B-ID");
        when(classConceptEditModel.getType()).thenReturn("subjekt");
        when(classConceptEditModel.getIsPublic()).thenReturn(Boolean.FALSE);

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(oldIri, classConceptEditModel, model, null);

        assertTrue(result.iriChanged);
        Resource newResource = model.getResource(result.newConceptIRI);

        Resource neverejny = model.getResource(OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ);
        assertFalse(newResource.hasProperty(RDF.type, verejny));
        assertFalse(newResource.hasProperty(RDF.type, neverejny));

        Resource tspType = model.getResource(OFN_NAMESPACE + TSP);
        assertTrue(newResource.hasProperty(RDF.type, tspType));
    }
}
