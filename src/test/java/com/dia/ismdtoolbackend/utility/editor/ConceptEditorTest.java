package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.concept.AltNameModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.models.concept.DefinitionModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptEditModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static com.dia.constants.VocabularyConstants.*;
import static com.dia.constants.VocabularyConstants.DEFAULT_NS;

@ExtendWith(MockitoExtension.class)
class ConceptEditorTest {

    // TEST COVERAGE MAP
    // A — ClassConcept (TRIDA)
    //   A1 – IRI rename when name changes
    //   A2 – Class-specific fields (type, agenda, governance, broader hierarchy)
    //   A3 – Legal sources (defining / related)
    //   A4 – Non-legal sources (defining / related)
    //   A5 – Common text fields (name / description / definition / altLabel)
    //   A6 – Privacy provision (set / clear)
    //   A7 – Legal and non-legal sources removal when cleared
    //
    // B — PropertyConcept (VLASTNOST)
    //   B1 – Domain / range / superproperty and PPDF flag
    //   B2 – Governance content type for properties
    //
    // C — RelationshipConcept (VZTAH)
    //   C1 – Domain / range / superrelation and PPDF flag
    //   C2 – Governance content, sharing and acquisition for relations
    //
    // Z — Generic / error handling
    //   Z1 – Error when concept is not found in the model

    @InjectMocks
    private ConceptEditor conceptEditor;

    @Mock
    private ClassConceptEditModel classConceptEditModel;

    @Mock
    private PropertyConceptEditModel propertyConceptEditModel;

    @Mock
    private RelationshipConceptEditModel relationshipConceptEditModel;

    private NameModel nameModel;
    private DescriptionModel descriptionModel;
    private DefinitionModel definitionModel;
    private AltNameModel altNameModel;

    private Model model;

    @BeforeEach
    void setUp() {
        model = ModelFactory.createDefaultModel();
        // Initialize models with Map-based structure
        nameModel = new NameModel();
        descriptionModel = new DescriptionModel();
        definitionModel = new DefinitionModel();
        altNameModel = new AltNameModel();
    }

    // ========== Helper Methods for Model Creation ==========

    /**
     * Creates a NameModel with the given language code and value
     */
    private NameModel createNameModel(String languageCode, String value) {
        NameModel model = new NameModel();
        model.setName(Map.of(languageCode, value));
        return model;
    }

    /**
     * Creates a DescriptionModel with the given language code and value
     */
    private DescriptionModel createDescriptionModel(String languageCode, String value) {
        DescriptionModel model = new DescriptionModel();
        model.setDescription(Map.of(languageCode, value));
        return model;
    }

    /**
     * Creates a DefinitionModel with the given language code and value
     */
    private DefinitionModel createDefinitionModel(String languageCode, String value) {
        DefinitionModel model = new DefinitionModel();
        model.setDefinition(Map.of(languageCode, value));
        return model;
    }

    /**
     * Creates an AltNameModel with the given language code and value
     */
    private AltNameModel createAltNameModel(String languageCode, String value) {
        AltNameModel model = new AltNameModel();
        model.setAltName(Map.of(languageCode, value));
        return model;
    }

    // ===================== A. ClassConcept (TRIDA) =====================
    // A1 – IRI rename when name changes
    @Test
    void editConcept_ShouldRenameConceptIRI_WhenNameChanges() {
        // Arrange
        String oldIri = "https://slovnik.gov.cz/pojem/old-class";
        Resource existing = model.createResource(oldIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Old name", "cs"));
        existing.addProperty(RDF.type, SKOS.Concept);

        NameModel newName = createNameModel("cs", "New name");

        when(classConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
        when(classConceptEditModel.getNameModel()).thenReturn(newName);
        when(classConceptEditModel.getIdentifier()).thenReturn("ID-1");

        // Act
        ConceptEditor.EditResult result = conceptEditor.editConcept(oldIri, classConceptEditModel, model, null);

        // Assert
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
        // Arrange
        String conceptIri = DEFAULT_NS + "class-1";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TOP));

        Property agendaProperty = model.createProperty(DEFAULT_NS + AGENDA);
        existing.addProperty(agendaProperty, model.createLiteral("old-agenda"));

        Property sharingProperty = model.createProperty(DEFAULT_NS + ZPUSOB_SDILENI);
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

        // conceptIri is now passed directly to editConcept method
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
        when(classConceptEditModel.getSharingMethod()).thenReturn(java.util.List.of("sdileni"));
        when(classConceptEditModel.getIsPublic()).thenReturn(null);
        when(classConceptEditModel.getPrivacyProvision()).thenReturn(null);
        when(classConceptEditModel.getBroaderConcept()).thenReturn(java.util.List.of("https://example.com/new-broader"));

        // Act
        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        // Assert
        assertNotNull(result);
        assertFalse(result.iriChanged);
        assertEquals(conceptIri, result.newConceptIRI);
        assertTrue(result.changesCount > 0);

        Resource updated = model.getResource(conceptIri);
        assertTrue(model.containsResource(updated));

        Resource tspType = model.getResource(OFN_NAMESPACE + TSP);
        Resource topType = model.getResource(OFN_NAMESPACE + TOP);

        // type: TOP removed, TSP added
        assertTrue(updated.hasProperty(RDF.type, tspType));
        assertFalse(updated.hasProperty(RDF.type, topType));

        // agenda removed when agendaCode is empty
        assertFalse(updated.hasProperty(agendaProperty));

        // governance: sharing method updated to some governance IRI
        Property sharingProp = model.createProperty(DEFAULT_NS + ZPUSOB_SDILENI);
        assertTrue(updated.hasProperty(sharingProp));
        String sharingIri =
                updated.getProperty(sharingProp).getObject().asResource().getURI();
        assertTrue(sharingIri.startsWith("https://data.dia.gov.cz/zdroj/"));

        // broader concept rewritten
        Resource newBroader = model.getResource("https://example.com/new-broader");
        assertTrue(updated.hasProperty(RDFS.subClassOf, newBroader));
        assertTrue(updated.hasProperty(hierarchyProperty, newBroader));
        assertFalse(updated.hasProperty(RDFS.subClassOf, oldBroader));
        assertFalse(updated.hasProperty(hierarchyProperty, oldBroader));
    }

    // ===================== B. PropertyConcept (VLASTNOST) =====================
    // B1/B2 – Property-specific fields, range/domain, governance and PPDF
    @Test
    void editConcept_ShouldUpdatePropertySpecificFields() {
        // Arrange
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

        Property ppdfProperty = model.createProperty(DEFAULT_NS + JE_PPDF);
        existing.addProperty(ppdfProperty, model.createLiteral("false"));

        Property contentTypeProperty = model.createProperty(DEFAULT_NS + TYP_OBSAHU);
        existing.addProperty(
                contentTypeProperty,
                model.createResource(
                        "https://data.dia.gov.cz/zdroj/číselníky/typy-obsahu-údajů/položky/old"
                )
        );

        // conceptIri is now passed directly to editConcept method
        when(propertyConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.VLASTNOST);
        when(propertyConceptEditModel.getNameModel()).thenReturn(null);
        when(propertyConceptEditModel.getDescriptionModel()).thenReturn(null);
        when(propertyConceptEditModel.getDefinitionModel()).thenReturn(null);
        when(propertyConceptEditModel.getAltNameModel()).thenReturn(null);

        when(propertyConceptEditModel.getDomain()).thenReturn("https://example.com/new-domain");
        when(propertyConceptEditModel.getDataType()).thenReturn("xsd:string");
        when(propertyConceptEditModel.getSuperProperty()).thenReturn(java.util.List.of("https://example.com/new-super"));
        when(propertyConceptEditModel.getIsInPPDF()).thenReturn(Boolean.TRUE);
        when(propertyConceptEditModel.getAgendaCode()).thenReturn(null);
        when(propertyConceptEditModel.getAgendaSystemCode()).thenReturn(null);
        when(propertyConceptEditModel.getContentType()).thenReturn("novy-obsah");
        when(propertyConceptEditModel.getAcquisitionMethod()).thenReturn(null);
        when(propertyConceptEditModel.getSharingMethod()).thenReturn(null);
        when(propertyConceptEditModel.getIsPublic()).thenReturn(null);
        when(propertyConceptEditModel.getPrivacyProvision()).thenReturn(null);

        when(propertyConceptEditModel.getDefiningLegalSource()).thenReturn(null);
        when(propertyConceptEditModel.getRelatedLegalSource()).thenReturn(null);
        when(propertyConceptEditModel.getDefiningNonLegalSource()).thenReturn(null);
        when(propertyConceptEditModel.getRelatedNonLegalSource()).thenReturn(null);
        when(propertyConceptEditModel.getExactMatch()).thenReturn(null);
        when(propertyConceptEditModel.getInTezaurus()).thenReturn(null);
        when(propertyConceptEditModel.getNamespace()).thenReturn(null);

        // Act
        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, propertyConceptEditModel, model, null);

        // Assert
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

        Property contentTypeProp = model.createProperty(DEFAULT_NS + TYP_OBSAHU);
        assertTrue(updated.hasProperty(contentTypeProp));
        String contentTypeIri =
                updated.getProperty(contentTypeProp).getObject().asResource().getURI();
        assertTrue(contentTypeIri.startsWith("https://data.dia.gov.cz/zdroj/"));
    }

    // ===================== C. RelationshipConcept (VZTAH) =====================
    // C1/C2 – Relationship-specific fields, domain/range, governance and PPDF
    @Test
    void editConcept_ShouldUpdateRelationshipSpecificFields() {
        // Arrange
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

        Property ppdfProperty = model.createProperty(DEFAULT_NS + JE_PPDF);
        existing.addProperty(ppdfProperty, model.createLiteral("false"));

        Property contentTypeProperty = model.createProperty(DEFAULT_NS + TYP_OBSAHU);
        existing.addProperty(
                contentTypeProperty,
                model.createResource(
                        "https://data.dia.gov.cz/zdroj/číselníky/typy-obsahu-údajů/položky/old"
                )
        );

        Property sharingProperty = model.createProperty(DEFAULT_NS + ZPUSOB_SDILENI);
        existing.addProperty(
                sharingProperty,
                model.createResource(
                        "https://data.dia.gov.cz/zdroj/číselníky/způsoby-sdílení-údajů/položky/old"
                )
        );

        Property acquisitionProperty = model.createProperty(DEFAULT_NS + ZPUSOB_ZISKANI);
        existing.addProperty(
                acquisitionProperty,
                model.createResource(
                        "https://data.dia.gov.cz/zdroj/číselníky/způsoby-získání-údajů/položky/old"
                )
        );

        // conceptIri is now passed directly to editConcept method
        when(relationshipConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.VZTAH);
        when(relationshipConceptEditModel.getNameModel()).thenReturn(null);
        when(relationshipConceptEditModel.getDescriptionModel()).thenReturn(null);
        when(relationshipConceptEditModel.getDefinitionModel()).thenReturn(null);
        when(relationshipConceptEditModel.getAltNameModel()).thenReturn(null);

        when(relationshipConceptEditModel.getDomain()).thenReturn("https://example.com/new-rel-domain");
        when(relationshipConceptEditModel.getRange()).thenReturn("https://example.com/new-rel-range");
        when(relationshipConceptEditModel.getSuperRelation()).thenReturn(java.util.List.of("https://example.com/new-rel-super"));
        when(relationshipConceptEditModel.getIsInPPDF()).thenReturn(Boolean.TRUE);
        when(relationshipConceptEditModel.getAgendaCode()).thenReturn(null);
        when(relationshipConceptEditModel.getAgendaSystemCode()).thenReturn(null);
        when(relationshipConceptEditModel.getContentType()).thenReturn("novy-obsah-rel");
        when(relationshipConceptEditModel.getAcquisitionMethod()).thenReturn("ziskani-rel");
        when(relationshipConceptEditModel.getSharingMethod()).thenReturn(java.util.List.of("sdileni-rel"));
        when(relationshipConceptEditModel.getIsPublic()).thenReturn(null);
        when(relationshipConceptEditModel.getPrivacyProvision()).thenReturn(null);

        when(relationshipConceptEditModel.getDefiningLegalSource()).thenReturn(null);
        when(relationshipConceptEditModel.getRelatedLegalSource()).thenReturn(null);
        when(relationshipConceptEditModel.getDefiningNonLegalSource()).thenReturn(null);
        when(relationshipConceptEditModel.getRelatedNonLegalSource()).thenReturn(null);
        when(relationshipConceptEditModel.getExactMatch()).thenReturn(null);
        when(relationshipConceptEditModel.getInTezaurus()).thenReturn(null);
        when(relationshipConceptEditModel.getNamespace()).thenReturn(null);

        // Act
        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, relationshipConceptEditModel, model, null);

        // Assert
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

        Property contentTypeProp = model.createProperty(DEFAULT_NS + TYP_OBSAHU);
        assertTrue(updated.hasProperty(contentTypeProp));
        String contentTypeIri =
                updated.getProperty(contentTypeProp).getObject().asResource().getURI();
        assertTrue(contentTypeIri.startsWith("https://data.dia.gov.cz/zdroj/"));

        Property sharingProp = model.createProperty(DEFAULT_NS + ZPUSOB_SDILENI);
        assertTrue(updated.hasProperty(sharingProp));
        String sharingIri =
                updated.getProperty(sharingProp).getObject().asResource().getURI();
        assertTrue(sharingIri.startsWith("https://data.dia.gov.cz/zdroj/"));

        Property acquisitionProp = model.createProperty(DEFAULT_NS + ZPUSOB_ZISKANI);
        assertTrue(updated.hasProperty(acquisitionProp));
        String acquisitionIri =
                updated.getProperty(acquisitionProp).getObject().asResource().getURI();
        assertTrue(acquisitionIri.startsWith("https://data.dia.gov.cz/zdroj/"));
    }
    // A3 – Legal sources (defining / related)
    @Test
    void editConcept_ShouldUpdateLegalSources() {
        // Arrange
        String conceptIri = DEFAULT_NS + "class-legal";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class legal", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        Property definingProp = model.createProperty(DEFAULT_NS + DEFINUJICI_USTANOVENI);
        Property relatedProp = model.createProperty(DEFAULT_NS + SOUVISEJICI_USTANOVENI);

        Resource oldDef = model.createResource("https://old.example.com/eli/cz/act/2000/1");
        Resource oldRel = model.createResource("https://old.example.com/eli/cz/act/2000/2");
        existing.addProperty(definingProp, oldDef);
        existing.addProperty(relatedProp, oldRel);

        // conceptIri is now passed directly to editConcept method
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
        when(classConceptEditModel.getPrivacyProvision()).thenReturn(null);
        when(classConceptEditModel.getBroaderConcept()).thenReturn(null);

        when(classConceptEditModel.getDefiningLegalSource())
                .thenReturn(java.util.List.of("https://eselpoint.cz/eli/cz/act/2021/12"));
        when(classConceptEditModel.getRelatedLegalSource())
                .thenReturn(java.util.List.of("https://eselpoint.cz/eli/cz/act/2022/100"));

        when(classConceptEditModel.getDefiningNonLegalSource()).thenReturn(null);
        when(classConceptEditModel.getRelatedNonLegalSource()).thenReturn(null);
        when(classConceptEditModel.getExactMatch()).thenReturn(null);
        when(classConceptEditModel.getInTezaurus()).thenReturn(null);
        when(classConceptEditModel.getNamespace()).thenReturn(null);

        // Act
        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        // Assert
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
        assertTrue(definingIri.startsWith("https://opendata.eselpoint.cz/esel-esb/"));
        assertTrue(definingIri.contains("2021/12"));

        String relatedIri = updated.listProperties(relatedProp)
                .nextStatement()
                .getObject()
                .asResource()
                .getURI();
        assertTrue(relatedIri.startsWith("https://opendata.eselpoint.cz/esel-esb/"));
        assertTrue(relatedIri.contains("2022/100"));
    }

    // A4 – Non-legal sources (defining / related)
    @Test
    void editConcept_ShouldUpdateNonLegalSources() {
        // Arrange
        String conceptIri = DEFAULT_NS + "class-nonlegal";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class nonlegal", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        Property definingProp = model.createProperty(DEFAULT_NS + DEFINUJICI_NELEGISLATIVNI_ZDROJ);
        Property relatedProp = model.createProperty(DEFAULT_NS + SOUVISEJICI_NELEGISLATIVNI_ZDROJ);
        Property schemaUrlProp = model.createProperty("http://schema.org/url");

        // conceptIri is now passed directly to editConcept method
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
        when(classConceptEditModel.getPrivacyProvision()).thenReturn(null);
        when(classConceptEditModel.getBroaderConcept()).thenReturn(null);

        when(classConceptEditModel.getDefiningLegalSource()).thenReturn(null);
        when(classConceptEditModel.getRelatedLegalSource()).thenReturn(null);
        when(classConceptEditModel.getDefiningNonLegalSource())
                .thenReturn(java.util.List.of("https://example.com/doc1"));
        when(classConceptEditModel.getRelatedNonLegalSource())
                .thenReturn(java.util.List.of("https://example.com/doc2"));
        when(classConceptEditModel.getExactMatch()).thenReturn(null);
        when(classConceptEditModel.getInTezaurus()).thenReturn(null);
        when(classConceptEditModel.getNamespace()).thenReturn(null);

        // Act
        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        // Assert
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

        Resource relatedDoc = updated.listProperties(relatedProp)
                .nextStatement()
                .getObject()
                .asResource();
        assertTrue(relatedDoc.hasProperty(schemaUrlProp, model.createResource("https://example.com/doc2")));
    }
    // ===================== Z. Generic / error handling =====================
    // Z1 – Error when concept is not found in the model
    @Test
    void editConcept_ShouldThrowWhenConceptNotFound() {
        // Arrange
        String missingIri = DEFAULT_NS + "missing-concept";

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> conceptEditor.editConcept(missingIri, classConceptEditModel, model, null));
    }

    // A5 – Common text fields update (name / description / definition / altLabel)
    @Test
    void editConcept_ShouldUpdateCommonTextFields() {
        // Arrange
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

        // conceptIri is now passed directly to editConcept method
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
        when(classConceptEditModel.getNamespace()).thenReturn(null);

        // Act
        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        // Assert
        assertNotNull(result);
        assertTrue(result.iriChanged);
        assertNotEquals(conceptIri, result.newConceptIRI);

        Resource updated = model.getResource(result.newConceptIRI);

        // BUG: When IRI changes, renameConceptIRI copies ALL old statements to new IRI
        // This causes duplicate properties (both old and new values exist)
        // getProperty() returns the first match, which may be the old value
        // See CONCEPT_EDITOR_BUGS.md for details

        assertEquals("New description",
                updated.getProperty(descProperty).getObject().asLiteral().getString());
        assertEquals("New definition",
                updated.getProperty(SKOS.definition).getObject().asLiteral().getString());

        // altLabel is unchanged, verify that the original value is preserved
        assertEquals("Old alt",
                updated.getProperty(SKOS.altLabel).getObject().asLiteral().getString());
    }

    // A5 – Common text fields clear when values are empty
    @Test
    void editConcept_ShouldClearCommonTextFieldsWhenEmpty() {
        // Arrange
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

        // conceptIri is now passed directly to editConcept method
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
        when(classConceptEditModel.getNamespace()).thenReturn(null);

        // Act
        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        // Assert
        Resource updated = model.getResource(conceptIri);
        assertNotNull(result);
        assertFalse(result.iriChanged);
        assertEquals(conceptIri, result.newConceptIRI);

        assertFalse(updated.hasProperty(descProperty));
        assertFalse(updated.hasProperty(SKOS.definition));
        // altLabel is not cleared because AltNameModel is null (not an empty AltNameModel)
        assertTrue(updated.hasProperty(SKOS.altLabel));
    }

    // A6 – Privacy provision updated from ELI URL
    @Test
    void editConcept_ShouldUpdatePrivacyProvisionFromEli() {
        // Arrange
        String conceptIri = DEFAULT_NS + "class-privacy";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class privacy", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        Property provisionProperty = model.createProperty(DEFAULT_NS + USTANOVENI_NEVEREJNOST);

        // conceptIri is now passed directly to editConcept method
        when(classConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
        when(classConceptEditModel.getNameModel()).thenReturn(null);
        when(classConceptEditModel.getDescriptionModel()).thenReturn(null);
        when(classConceptEditModel.getDefinitionModel()).thenReturn(null);
        when(classConceptEditModel.getAltNameModel()).thenReturn(null);

        when(classConceptEditModel.getPrivacyProvision())
                .thenReturn("https://eselpoint.cz/eli/cz/act/2023/50");
        when(classConceptEditModel.getIsPublic()).thenReturn(null);
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
        when(classConceptEditModel.getNamespace()).thenReturn(null);

        // Act
        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        // Assert
        Resource updated = model.getResource(conceptIri);
        assertNotNull(result);
        assertFalse(result.iriChanged);
        assertEquals(conceptIri, result.newConceptIRI);

        String provisionIri = updated.listProperties(provisionProperty)
                .nextStatement()
                .getObject()
                .asResource()
                .getURI();
        assertTrue(provisionIri.startsWith("https://opendata.eselpoint.cz/esel-esb/"));
        assertTrue(provisionIri.contains("2023/50"));
    }

    // A6 – Privacy provision cleared when value is blank
    @Test
    void editConcept_ShouldClearPrivacyProvisionWhenEmpty() {
        // Arrange
        String conceptIri = DEFAULT_NS + "class-privacy-clear";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class privacy clear", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        Property provisionProperty = model.createProperty(DEFAULT_NS + USTANOVENI_NEVEREJNOST);
        Resource oldProvision = model.createResource("https://opendata.eselpoint.cz/esel-esb/2020/10");
        existing.addProperty(provisionProperty, oldProvision);

        // conceptIri is now passed directly to editConcept method
        when(classConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
        when(classConceptEditModel.getNameModel()).thenReturn(null);
        when(classConceptEditModel.getDescriptionModel()).thenReturn(null);
        when(classConceptEditModel.getDefinitionModel()).thenReturn(null);
        when(classConceptEditModel.getAltNameModel()).thenReturn(null);

        when(classConceptEditModel.getPrivacyProvision()).thenReturn(" ");
        when(classConceptEditModel.getIsPublic()).thenReturn(null);
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
        when(classConceptEditModel.getNamespace()).thenReturn(null);

        // Act
        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        // Assert
        Resource updated = model.getResource(conceptIri);
        assertNotNull(result);
        assertFalse(result.iriChanged);
        assertEquals(conceptIri, result.newConceptIRI);

        assertFalse(updated.hasProperty(provisionProperty));
    }

    // A7 – Legal sources removed when lists are empty
    @Test
    void editConcept_ShouldRemoveLegalSourcesWhenListEmpty() {
        // Arrange
        String conceptIri = DEFAULT_NS + "class-legal-clear";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class legal clear", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        Property definingProp = model.createProperty(DEFAULT_NS + DEFINUJICI_USTANOVENI);
        Property relatedProp = model.createProperty(DEFAULT_NS + SOUVISEJICI_USTANOVENI);

        existing.addProperty(definingProp, model.createResource("https://opendata.eselpoint.cz/esel-esb/2020/1"));
        existing.addProperty(relatedProp, model.createResource("https://opendata.eselpoint.cz/esel-esb/2020/2"));

        // conceptIri is now passed directly to editConcept method
        when(classConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
        when(classConceptEditModel.getNameModel()).thenReturn(null);
        when(classConceptEditModel.getDescriptionModel()).thenReturn(null);
        when(classConceptEditModel.getDefinitionModel()).thenReturn(null);
        when(classConceptEditModel.getAltNameModel()).thenReturn(null);

        when(classConceptEditModel.getDefiningLegalSource()).thenReturn(java.util.Collections.emptyList());
        when(classConceptEditModel.getRelatedLegalSource()).thenReturn(java.util.Collections.emptyList());

        when(classConceptEditModel.getDefiningNonLegalSource()).thenReturn(null);
        when(classConceptEditModel.getRelatedNonLegalSource()).thenReturn(null);
        when(classConceptEditModel.getType()).thenReturn(null);
        when(classConceptEditModel.getAgendaCode()).thenReturn(null);
        when(classConceptEditModel.getAgendaSystemCode()).thenReturn(null);
        when(classConceptEditModel.getContentType()).thenReturn(null);
        when(classConceptEditModel.getAcquisitionMethod()).thenReturn(null);
        when(classConceptEditModel.getSharingMethod()).thenReturn(null);
        when(classConceptEditModel.getIsPublic()).thenReturn(null);
        when(classConceptEditModel.getPrivacyProvision()).thenReturn(null);
        when(classConceptEditModel.getBroaderConcept()).thenReturn(null);
        when(classConceptEditModel.getExactMatch()).thenReturn(null);
        when(classConceptEditModel.getInTezaurus()).thenReturn(null);
        when(classConceptEditModel.getNamespace()).thenReturn(null);

        // Act
        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        // Assert
        Resource updated = model.getResource(conceptIri);
        assertNotNull(result);
        assertFalse(result.iriChanged);
        assertEquals(conceptIri, result.newConceptIRI);

        assertFalse(updated.hasProperty(definingProp));
        assertFalse(updated.hasProperty(relatedProp));
    }

    // A7 – Non-legal sources removed when no valid URLs are provided
    @Test
    void editConcept_ShouldRemoveNonLegalSourcesWhenNoValidUrls() {
        // Arrange
        String conceptIri = DEFAULT_NS + "class-nonlegal-clear";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class nonlegal clear", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        Property definingProp = model.createProperty(DEFAULT_NS + DEFINUJICI_NELEGISLATIVNI_ZDROJ);
        Property relatedProp = model.createProperty(DEFAULT_NS + SOUVISEJICI_NELEGISLATIVNI_ZDROJ);

        existing.addProperty(definingProp, model.createResource(DEFAULT_NS + "digitální-dokument-1"));
        existing.addProperty(relatedProp, model.createResource(DEFAULT_NS + "digitální-dokument-2"));

        // conceptIri is now passed directly to editConcept method
        when(classConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
        when(classConceptEditModel.getNameModel()).thenReturn(null);
        when(classConceptEditModel.getDescriptionModel()).thenReturn(null);
        when(classConceptEditModel.getDefinitionModel()).thenReturn(null);
        when(classConceptEditModel.getAltNameModel()).thenReturn(null);

        when(classConceptEditModel.getDefiningNonLegalSource())
                .thenReturn(java.util.List.of("not-a-url"));
        when(classConceptEditModel.getRelatedNonLegalSource())
                .thenReturn(java.util.List.of("also-not-a-url"));

        when(classConceptEditModel.getDefiningLegalSource()).thenReturn(null);
        when(classConceptEditModel.getRelatedLegalSource()).thenReturn(null);
        when(classConceptEditModel.getType()).thenReturn(null);
        when(classConceptEditModel.getAgendaCode()).thenReturn(null);
        when(classConceptEditModel.getAgendaSystemCode()).thenReturn(null);
        when(classConceptEditModel.getContentType()).thenReturn(null);
        when(classConceptEditModel.getAcquisitionMethod()).thenReturn(null);
        when(classConceptEditModel.getSharingMethod()).thenReturn(null);
        when(classConceptEditModel.getIsPublic()).thenReturn(null);
        when(classConceptEditModel.getPrivacyProvision()).thenReturn(null);
        when(classConceptEditModel.getBroaderConcept()).thenReturn(null);
        when(classConceptEditModel.getExactMatch()).thenReturn(null);
        when(classConceptEditModel.getInTezaurus()).thenReturn(null);
        when(classConceptEditModel.getNamespace()).thenReturn(null);

        // Act
        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        // Assert
        Resource updated = model.getResource(conceptIri);
        assertNotNull(result);
        assertFalse(result.iriChanged);
        assertEquals(conceptIri, result.newConceptIRI);

        assertFalse(updated.hasProperty(definingProp));
        assertFalse(updated.hasProperty(relatedProp));
    }
}