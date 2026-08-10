package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptEditModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dia.constants.VocabularyConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Granular branch coverage for the per-field updaters in ConceptFieldUpdaters,
 * exercised through ConceptEditor with real edit models. Focus: the
 * "clear-when-empty", "no-op-when-unchanged", "URI vs literal", and rdf:type
 * transition branches that the higher-level tests don't all hit.
 * <p>
 * Edits keep the prefLabel unchanged so no IRI rename occurs — the concept is
 * read back from its original IRI.
 */
class ConceptEditorFieldEdgeCasesTest {

    private ConceptEditor editor;
    private Model model;

    @BeforeEach
    void setUp() {
        editor = new ConceptEditor();
        model = ModelFactory.createDefaultModel();
    }

    private ClassConceptEditModel classModel() {
        ClassConceptEditModel m = new ClassConceptEditModel();
        m.setConceptType("TRIDA");
        return m;
    }

    private PropertyConceptEditModel propertyModel() {
        PropertyConceptEditModel m = new PropertyConceptEditModel();
        m.setConceptType("VLASTNOST");
        return m;
    }

    private RelationshipConceptEditModel relationshipModel() {
        RelationshipConceptEditModel m = new RelationshipConceptEditModel();
        m.setConceptType("VZTAH");
        return m;
    }

    private Resource seedClass(String iri) {
        Resource r = model.createResource(iri);
        r.addProperty(SKOS.prefLabel, model.createLiteral("Třída", "cs"));
        r.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));
        return r;
    }

    private Resource seedProperty(String iri) {
        Resource r = model.createResource(iri);
        r.addProperty(SKOS.prefLabel, model.createLiteral("Vlastnost", "cs"));
        r.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + VLASTNOST));
        return r;
    }

    // ---- updateClassType: rdf:type subjekt/objekt transitions ----

    @Test
    void classType_addsTSP_whenNewlySubjekt() {
        String iri = DEFAULT_NS + "ct-add-tsp";
        seedClass(iri);
        ClassConceptEditModel m = classModel();
        m.setType("Typ subjektu práva");

        editor.editConcept(iri, m, model, null);

        assertTrue(model.getResource(iri).hasProperty(RDF.type, model.getResource(OFN_NAMESPACE + TSP)));
    }

    @Test
    void classType_removesTSP_whenNoLongerSubjekt() {
        String iri = DEFAULT_NS + "ct-remove-tsp";
        Resource r = seedClass(iri);
        r.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TSP));
        ClassConceptEditModel m = classModel();
        m.setType("Typ objektu práva");   // objekt, not subjekt

        editor.editConcept(iri, m, model, null);

        Resource updated = model.getResource(iri);
        assertFalse(updated.hasProperty(RDF.type, model.getResource(OFN_NAMESPACE + TSP)),
                "TSP should be removed when no longer subjekt");
        assertTrue(updated.hasProperty(RDF.type, model.getResource(OFN_NAMESPACE + TOP)),
                "TOP should be added for objekt");
    }

    @Test
    void classType_noChange_whenStillSubjekt() {
        String iri = DEFAULT_NS + "ct-noop";
        Resource r = seedClass(iri);
        r.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TSP));
        ClassConceptEditModel m = classModel();
        m.setType("Typ subjektu práva");   // still subjekt

        editor.editConcept(iri, m, model, null);

        assertTrue(model.getResource(iri).hasProperty(RDF.type, model.getResource(OFN_NAMESPACE + TSP)));
    }

    @Test
    void classType_nullType_isNoOp() {
        String iri = DEFAULT_NS + "ct-null";
        seedClass(iri);
        long before = model.size();
        ClassConceptEditModel m = classModel();
        m.setType(null);

        editor.editConcept(iri, m, model, null);
        assertEquals(before, model.size());
    }

    // ---- updateDomainRange: clear / no-op / URI passthrough ----

    @Test
    void domain_clearedWhenBlank() {
        String iri = DEFAULT_NS + "dom-clear";
        Resource r = seedProperty(iri);
        r.addProperty(RDFS.domain, model.createResource(DEFAULT_NS + "old-domain"));
        PropertyConceptEditModel m = propertyModel();
        m.setDomain("");   // blank → clear

        editor.editConcept(iri, m, model, null);
        assertFalse(model.getResource(iri).hasProperty(RDFS.domain));
    }

    @Test
    void domain_noOpWhenUnchanged() {
        String iri = DEFAULT_NS + "dom-noop";
        String domainIri = "https://example.org/Osoba";
        Resource r = seedProperty(iri);
        r.addProperty(RDFS.domain, model.createResource(domainIri));
        long before = model.size();
        PropertyConceptEditModel m = propertyModel();
        m.setDomain(domainIri);   // same URI

        editor.editConcept(iri, m, model, null);
        assertEquals(before, model.size(), "unchanged domain must not restage");
    }

    @Test
    void domain_blankWithNoExisting_isNoOp() {
        String iri = DEFAULT_NS + "dom-blank-none";
        seedProperty(iri);
        long before = model.size();
        PropertyConceptEditModel m = propertyModel();
        m.setDomain("   ");

        editor.editConcept(iri, m, model, null);
        assertEquals(before, model.size());
    }

    // ---- updateDataTypeRange: clear / change ----

    @Test
    void dataType_clearedWhenBlank() {
        String iri = DEFAULT_NS + "dt-clear";
        Resource r = seedProperty(iri);
        r.addProperty(RDFS.range, model.createResource("http://www.w3.org/2001/XMLSchema#string"));
        PropertyConceptEditModel m = propertyModel();
        m.setDataType("");

        editor.editConcept(iri, m, model, null);
        assertFalse(model.getResource(iri).hasProperty(RDFS.range));
    }

    // ---- updateBroaderConceptList: clear when emptied ----

    @Test
    void broaderConcept_clearedWhenEmptyList() {
        String iri = DEFAULT_NS + "broader-clear";
        Resource r = seedClass(iri);
        r.addProperty(RDFS.subClassOf, model.createResource(DEFAULT_NS + "parent"));
        ClassConceptEditModel m = classModel();
        m.setBroaderConcept(List.of());   // empty → clear

        editor.editConcept(iri, m, model, null);
        assertFalse(model.getResource(iri).hasProperty(RDFS.subClassOf));
    }

    @Test
    void broaderConcept_noOpWhenUnchanged() {
        String iri = DEFAULT_NS + "broader-noop";
        String parent = "https://example.org/Parent";
        Resource r = seedClass(iri);
        r.addProperty(RDFS.subClassOf, model.createResource(parent));
        ClassConceptEditModel m = classModel();
        m.setBroaderConcept(List.of(parent));

        long before = model.size();
        editor.editConcept(iri, m, model, null);
        assertEquals(before, model.size());
    }

    // ---- updateAgenda / updateAIS: blank clears ----

    @Test
    void agenda_blankClearsExisting() {
        String iri = DEFAULT_NS + "agenda-clear";
        Resource r = seedClass(iri);
        Property agendaProp = model.createProperty(DEFAULT_NS + AGENDOVY_104 + AGENDA_LONG);
        r.addProperty(agendaProp, model.createLiteral("A123"));
        ClassConceptEditModel m = classModel();
        m.setAgendaCode("");

        editor.editConcept(iri, m, model, null);
        assertFalse(model.getResource(iri).hasProperty(agendaProp));
    }

    // ---- updateAgenda / updateAIS: valid value writes the property ----

    @Test
    void agenda_validValueIsWritten() {
        String iri = DEFAULT_NS + "agenda-valid";
        seedClass(iri);
        Property agendaProp = model.createProperty(DEFAULT_NS + AGENDOVY_104 + AGENDA_LONG);
        ClassConceptEditModel m = classModel();
        m.setAgendaCode("A123");   // canonical agenda code

        editor.editConcept(iri, m, model, null);

        assertTrue(model.getResource(iri).hasProperty(agendaProp),
                "a valid agenda code must be written");
    }

    @Test
    void ais_validValueIsWritten() {
        String iri = DEFAULT_NS + "ais-valid";
        seedClass(iri);
        Property aisProp = model.createProperty(DEFAULT_NS + AGENDOVY_104 + UDAJE_AIS);
        ClassConceptEditModel m = classModel();
        m.setAgendaSystemCode("5378");   // AIS codes are numeric (^\d+$)

        editor.editConcept(iri, m, model, null);

        assertTrue(model.getResource(iri).hasProperty(aisProp),
                "a valid AIS code must be written");
    }

    // ---- updateCodeListDataset: create / clear blank-node structure ----

    @Test
    void codeListDataset_createsBlankNodeStructure_whenSet() {
        String iri = DEFAULT_NS + "codelist-create";
        seedClass(iri);
        Property instanceDefinedBy = model.createProperty(OFN_NAMESPACE + MA_INSTANCE_DEFINOVANE_CISELNIKEM);
        ClassConceptEditModel m = classModel();
        m.setCodeListIri("https://data.mvcr.gov.cz/zdroj/číselníky/ciselnik-1");
        m.setCodeListDataset("https://data.gov.cz/zdroj/datové-sady/ciselnik-1");

        editor.editConcept(iri, m, model, null);

        Resource updated = model.getResource(iri);
        assertTrue(updated.hasProperty(instanceDefinedBy), "code-list link must be created");
        Resource blankNode = updated.getProperty(instanceDefinedBy).getObject().asResource();
        Property datasetProp = model.createProperty(OFN_NAMESPACE_LEGAL + MA_V_NKOD_ZASTRESUJICI_DATOVOU_SADU);
        assertTrue(blankNode.hasProperty(datasetProp), "blank node must carry the dataset URL");
        assertTrue(blankNode.hasProperty(RDF.type, model.getResource(OFN_NAMESPACE_LEGAL + CISELNIK)),
                "blank node must be typed as číselník");
    }

    @Test
    void codeListDataset_removesExistingStructure_whenBlank() {
        String iri = DEFAULT_NS + "codelist-clear";
        Resource r = seedClass(iri);
        // seed an existing code-list blank-node structure
        Property instanceDefinedBy = model.createProperty(OFN_NAMESPACE + MA_INSTANCE_DEFINOVANE_CISELNIKEM);
        Property datasetProp = model.createProperty(OFN_NAMESPACE_LEGAL + MA_V_NKOD_ZASTRESUJICI_DATOVOU_SADU);
        Resource bn = model.createResource();
        bn.addProperty(RDF.type, model.getResource(OFN_NAMESPACE_LEGAL + CISELNIK));
        bn.addProperty(datasetProp, model.createResource("https://data.gov.cz/dataset/old"));
        r.addProperty(instanceDefinedBy, bn);

        ClassConceptEditModel m = classModel();
        m.setCodeListDataset("");   // blank → remove structure

        editor.editConcept(iri, m, model, null);

        assertFalse(model.getResource(iri).hasProperty(instanceDefinedBy),
                "code-list link must be removed");
        assertFalse(bn.hasProperty(datasetProp), "blank node statements must be removed");
    }

    @Test
    void codeListDataset_nullIsNoOp() {
        String iri = DEFAULT_NS + "codelist-null";
        seedClass(iri);
        long before = model.size();
        ClassConceptEditModel m = classModel();
        m.setCodeListDataset(null);

        editor.editConcept(iri, m, model, null);
        assertEquals(before, model.size());
    }

    // ---- named číselník: rewrite must leave no stale subject behind ----

    @Test
    void codeList_namedCiselnik_leavesNoStaleTriples_whenIriChanges() {
        String iri = DEFAULT_NS + "codelist-swap";
        Resource r = seedClass(iri);
        String oldCodeList = "https://data.mvcr.gov.cz/zdroj/číselníky/stary";
        String newCodeList = "https://data.mvcr.gov.cz/zdroj/číselníky/novy";

        Property instanceDefinedBy = model.createProperty(OFN_NAMESPACE + MA_INSTANCE_DEFINOVANE_CISELNIKEM);
        Property datasetProp = model.createProperty(OFN_NAMESPACE_LEGAL + MA_V_NKOD_ZASTRESUJICI_DATOVOU_SADU);
        Resource oldNode = model.createResource(oldCodeList);
        oldNode.addProperty(RDF.type, model.getResource(OFN_NAMESPACE_LEGAL + CISELNIK));
        oldNode.addProperty(datasetProp, model.createResource("https://data.gov.cz/zdroj/datové-sady/stara"));
        r.addProperty(instanceDefinedBy, oldNode);

        ClassConceptEditModel m = classModel();
        m.setCodeListIri(newCodeList);
        m.setCodeListDataset("https://data.gov.cz/zdroj/datové-sady/nova");

        editor.editConcept(iri, m, model, null);

        // The old číselník carries no skos:inScheme, so a leaked subject would be invisible
        // to the reconciler and could never be repaired — it must be gone entirely.
        assertFalse(model.containsResource(model.createResource(oldCodeList)),
                "old číselník subject must leave no triples behind");

        Resource updated = model.getResource(iri);
        Resource linked = updated.getProperty(instanceDefinedBy).getObject().asResource();
        assertEquals(newCodeList, linked.getURI(), "concept must link to the new číselník");
        assertTrue(linked.hasProperty(datasetProp), "new číselník must carry its dataset");
    }

    // ---- code-list completeness: both IRIs are mandatory together (class only) ----

    @Test
    void codeList_rejectsDatasetWithoutCodeListIri() {
        String iri = DEFAULT_NS + "codelist-incomplete-dataset";
        seedClass(iri);
        ClassConceptEditModel m = classModel();
        m.setCodeListDataset("https://data.gov.cz/zdroj/datové-sady/ciselnik-2");

        assertThrows(RuntimeException.class, () -> editor.editConcept(iri, m, model, null),
                "dataset without a číselník IRI must be rejected");
    }

    @Test
    void codeList_rejectsCodeListIriWithoutDataset() {
        String iri = DEFAULT_NS + "codelist-incomplete-iri";
        seedClass(iri);
        ClassConceptEditModel m = classModel();
        m.setCodeListIri("https://data.mvcr.gov.cz/zdroj/číselníky/ciselnik-2");

        assertThrows(RuntimeException.class, () -> editor.editConcept(iri, m, model, null),
                "číselník IRI without a dataset must be rejected");
    }

    @Test
    void codeList_rejectsMalformedCodeListIri() {
        String iri = DEFAULT_NS + "codelist-malformed-iri";
        seedClass(iri);
        ClassConceptEditModel m = classModel();
        m.setCodeListIri("nikoliv-iri");
        m.setCodeListDataset("https://data.gov.cz/zdroj/datové-sady/ciselnik-3");

        assertThrows(RuntimeException.class, () -> editor.editConcept(iri, m, model, null),
                "a non-absolute číselník IRI must be rejected");
    }

    // ---- updateSuperPropertyList (relationship superRelation): clear ----

    @Test
    void superRelation_clearedWhenEmpty() {
        String iri = DEFAULT_NS + "super-clear";
        Resource r = model.createResource(iri);
        r.addProperty(SKOS.prefLabel, model.createLiteral("Vztah", "cs"));
        r.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + VZTAH));
        r.addProperty(RDFS.subPropertyOf, model.createResource(DEFAULT_NS + "super"));
        RelationshipConceptEditModel m = relationshipModel();
        m.setSuperRelation(List.of());

        editor.editConcept(iri, m, model, null);
        assertFalse(model.getResource(iri).hasProperty(RDFS.subPropertyOf));
    }
}
