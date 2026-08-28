package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.concept.AltNameModel;
import com.dia.ismdtoolbackend.models.concept.DefinitionModel;
import com.dia.ismdtoolbackend.enums.ConceptType;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static com.dia.constants.VocabularyConstants.DEFAULT_NS;
import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;

/**
 * Characterization tests that pin the language-map handling asymmetry BEFORE the
 * ConceptEditor refactor, so the refactor provably preserves it:
 *
 *   - prefLabel / description / definition  → MERGE by language tag
 *     (a single-language edit keeps the other languages)
 *   - altLabel                              → FULL REPLACE
 *     (a single-language edit drops the other languages)
 *   - empty-string value for a tag          → deletes just that tag
 *   - blank / missing tag                   → falls back to DEFAULT_LANG
 *
 * These are intentionally written against current behavior; they must stay green
 * through every refactor step.
 */
class ConceptEditorLanguageMergeTest extends ConceptEditorTestBase {

    private static final Property DC_DESCRIPTION =
            org.apache.jena.rdf.model.ResourceFactory.createProperty("http://purl.org/dc/terms/description");

    private Map<String, String> langValues(Resource resource, Property property) {
        Map<String, String> out = new HashMap<>();
        StmtIterator it = resource.listProperties(property);
        while (it.hasNext()) {
            var stmt = it.next();
            var lit = stmt.getObject().asLiteral();
            String lang = lit.getLanguage() != null && !lit.getLanguage().isEmpty() ? lit.getLanguage() : DEFAULT_LANG;
            out.put(lang, lit.getString());
        }
        return out;
    }

    /** Like {@link #langValues} but keeps every value per language — altLabel legitimately repeats. */
    private Map<String, List<String>> allLangValues(Resource resource, Property property) {
        Map<String, List<String>> out = new HashMap<>();
        StmtIterator it = resource.listProperties(property);
        while (it.hasNext()) {
            var stmt = it.next();
            var lit = stmt.getObject().asLiteral();
            String lang = lit.getLanguage() != null && !lit.getLanguage().isEmpty() ? lit.getLanguage() : DEFAULT_LANG;
            out.computeIfAbsent(lang, k -> new java.util.ArrayList<>()).add(lit.getString());
        }
        out.values().forEach(java.util.Collections::sort);
        return out;
    }

    private void stubCommonNull() {
        lenient().when(classConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
        lenient().when(classConceptEditModel.getNameModel()).thenReturn(null);
        lenient().when(classConceptEditModel.getDescriptionModel()).thenReturn(null);
        lenient().when(classConceptEditModel.getDefinitionModel()).thenReturn(null);
        lenient().when(classConceptEditModel.getAltNameModel()).thenReturn(null);
        lenient().when(classConceptEditModel.getDefiningLegalSource()).thenReturn(null);
        lenient().when(classConceptEditModel.getRelatedLegalSource()).thenReturn(null);
        lenient().when(classConceptEditModel.getDefiningNonLegalSource()).thenReturn(null);
        lenient().when(classConceptEditModel.getRelatedNonLegalSource()).thenReturn(null);
        lenient().when(classConceptEditModel.getExactMatch()).thenReturn(null);
        lenient().when(classConceptEditModel.getInTezaurus()).thenReturn(null);
        lenient().when(classConceptEditModel.getType()).thenReturn(null);
        lenient().when(classConceptEditModel.getPrivacyProvisions()).thenReturn(null);
        lenient().when(classConceptEditModel.getBroaderConcept()).thenReturn(null);
        lenient().when(classConceptEditModel.getIsInPPDF()).thenReturn(null);
        lenient().when(classConceptEditModel.getAgendaCode()).thenReturn(null);
        lenient().when(classConceptEditModel.getAgendaSystemCode()).thenReturn(null);
        lenient().when(classConceptEditModel.getSharingMethod()).thenReturn(null);
        lenient().when(classConceptEditModel.getAcquisitionMethod()).thenReturn(null);
        lenient().when(classConceptEditModel.getContentType()).thenReturn(null);
        lenient().when(classConceptEditModel.getIsPublic()).thenReturn(null);
        lenient().when(classConceptEditModel.getCodeListDataset()).thenReturn(null);
    }

    private Resource seedBilingual(String iri, Property property) {
        Resource existing = model.createResource(iri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Jméno", "cs"));
        existing.addProperty(RDF.type, SKOS.Concept);
        if (!property.equals(SKOS.prefLabel)) {
            existing.addProperty(property, model.createLiteral("cs hodnota", "cs"));
        } else {
            // prefLabel case: replace the seeded single label with a bilingual pair
            existing.removeAll(SKOS.prefLabel);
            existing.addProperty(SKOS.prefLabel, model.createLiteral("cs hodnota", "cs"));
        }
        existing.addProperty(property, model.createLiteral("en value", "en"));
        return existing;
    }

    // prefLabel MERGE: editing only cs keeps en.
    // NOTE: changing the cs prefLabel renames the concept IRI, so we read the
    // merged labels back from the NEW IRI returned by EditResult.
    @Test
    void prefLabel_isMergedByLanguage_notReplaced() {
        String iri = DEFAULT_NS + "merge-name";
        seedBilingual(iri, SKOS.prefLabel);

        NameModel newName = new NameModel();
        newName.setName(Map.of("cs", "Nové cs"));

        stubCommonNull();
        when(classConceptEditModel.getNameModel()).thenReturn(newName);

        ConceptEditor.EditResult res = conceptEditor.editConcept(iri, classConceptEditModel, model, null);

        Map<String, String> result = langValues(model.getResource(res.newConceptIRI), SKOS.prefLabel);
        assertEquals("Nové cs", result.get("cs"), "cs should be updated");
        assertEquals("en value", result.get("en"), "en must survive the merge");
        assertEquals(2, result.size());
    }

    // description MERGE
    @Test
    void description_isMergedByLanguage_notReplaced() {
        String iri = DEFAULT_NS + "merge-desc";
        seedBilingual(iri, DC_DESCRIPTION);

        DescriptionModel newDesc = new DescriptionModel();
        newDesc.setDescription(Map.of("cs", "Nový popis"));

        stubCommonNull();
        when(classConceptEditModel.getDescriptionModel()).thenReturn(newDesc);

        conceptEditor.editConcept(iri, classConceptEditModel, model, null);

        Map<String, String> result = langValues(model.getResource(iri), DC_DESCRIPTION);
        assertEquals("Nový popis", result.get("cs"));
        assertEquals("en value", result.get("en"), "en description must survive the merge");
    }

    // definition MERGE
    @Test
    void definition_isMergedByLanguage_notReplaced() {
        String iri = DEFAULT_NS + "merge-def";
        seedBilingual(iri, SKOS.definition);

        DefinitionModel newDef = new DefinitionModel();
        newDef.setDefinition(Map.of("cs", "Nová definice"));

        stubCommonNull();
        when(classConceptEditModel.getDefinitionModel()).thenReturn(newDef);

        conceptEditor.editConcept(iri, classConceptEditModel, model, null);

        Map<String, String> result = langValues(model.getResource(iri), SKOS.definition);
        assertEquals("Nová definice", result.get("cs"));
        assertEquals("en value", result.get("en"), "en definition must survive the merge");
    }

    // altLabel FULL REPLACE: editing only cs drops en (the asymmetry)
    @Test
    void altLabel_isFullyReplaced_notMerged() {
        String iri = DEFAULT_NS + "replace-alt";
        Resource existing = model.createResource(iri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Jméno", "cs"));
        existing.addProperty(RDF.type, SKOS.Concept);
        existing.addProperty(SKOS.altLabel, model.createLiteral("cs alt", "cs"));
        existing.addProperty(SKOS.altLabel, model.createLiteral("en alt", "en"));

        AltNameModel newAlt = new AltNameModel();
        newAlt.setAltName(Map.of("cs", List.of("Nový alt")));

        stubCommonNull();
        when(classConceptEditModel.getAltNameModel()).thenReturn(newAlt);

        conceptEditor.editConcept(iri, classConceptEditModel, model, null);

        Map<String, String> result = langValues(model.getResource(iri), SKOS.altLabel);
        assertEquals("Nový alt", result.get("cs"));
        assertNull(result.get("en"), "en altLabel must be dropped — altLabel is full-replace");
        assertEquals(1, result.size());
    }

    // Several alt labels in one language survive the write (the Map<String,String> collapse bug).
    @Test
    void altLabel_severalLabelsInOneLanguage_allSurvive() {
        String iri = DEFAULT_NS + "multi-alt";
        Resource existing = model.createResource(iri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Jméno", "cs"));
        existing.addProperty(RDF.type, SKOS.Concept);

        AltNameModel newAlt = new AltNameModel();
        newAlt.setAltName(Map.of("cs", List.of("Obec", "Municipalita")));

        stubCommonNull();
        when(classConceptEditModel.getAltNameModel()).thenReturn(newAlt);

        conceptEditor.editConcept(iri, classConceptEditModel, model, null);

        assertEquals(List.of("Municipalita", "Obec"), allLangValues(model.getResource(iri), SKOS.altLabel).get("cs"),
                "both cs alt labels must be written — the old Map<String,String> kept only the last");
    }

    // Re-submitting the same multi-value set must not churn the graph (equality is order-insensitive).
    @Test
    void altLabel_resubmittingSameLabelsInDifferentOrder_isANoOp() {
        String iri = DEFAULT_NS + "multi-alt-noop";
        Resource existing = model.createResource(iri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Jméno", "cs"));
        existing.addProperty(RDF.type, SKOS.Concept);
        existing.addProperty(SKOS.altLabel, model.createLiteral("Obec", "cs"));
        existing.addProperty(SKOS.altLabel, model.createLiteral("Municipalita", "cs"));

        AltNameModel sameAlt = new AltNameModel();
        sameAlt.setAltName(Map.of("cs", List.of("Municipalita", "Obec")));

        stubCommonNull();
        when(classConceptEditModel.getAltNameModel()).thenReturn(sameAlt);

        conceptEditor.editConcept(iri, classConceptEditModel, model, null);

        assertEquals(List.of("Municipalita", "Obec"), allLangValues(model.getResource(iri), SKOS.altLabel).get("cs"));
    }

    // empty-string value for a tag deletes just that tag from a merged field
    @Test
    void emptyStringValue_deletesOnlyThatLanguageTag() {
        String iri = DEFAULT_NS + "merge-name-clear-one";
        seedBilingual(iri, SKOS.prefLabel);

        NameModel newName = new NameModel();
        Map<String, String> m = new HashMap<>();
        m.put("en", "");           // clear en
        newName.setName(m);

        stubCommonNull();
        when(classConceptEditModel.getNameModel()).thenReturn(newName);

        conceptEditor.editConcept(iri, classConceptEditModel, model, null);

        Map<String, String> result = langValues(model.getResource(iri), SKOS.prefLabel);
        assertEquals("cs hodnota", result.get("cs"), "cs must remain");
        assertNull(result.get("en"), "en cleared via empty string");
        assertEquals(1, result.size());
    }

    // blank/missing tag falls back to DEFAULT_LANG.
    // Seed with a non-default language only ("xx") so the assertion holds
    // regardless of what DEFAULT_LANG resolves to.
    @Test
    void blankLanguageTag_fallsBackToDefaultLang() {
        String iri = DEFAULT_NS + "merge-name-blanktag";
        Resource existing = model.createResource(iri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Seed", "xx"));
        existing.addProperty(RDF.type, SKOS.Concept);

        NameModel newName = new NameModel();
        Map<String, String> m = new HashMap<>();
        m.put("", "Bez jazyka");   // blank tag
        newName.setName(m);

        stubCommonNull();
        when(classConceptEditModel.getNameModel()).thenReturn(newName);

        // Adding a label under DEFAULT_LANG changes the name → IRI rename; read from new IRI.
        ConceptEditor.EditResult res = conceptEditor.editConcept(iri, classConceptEditModel, model, null);

        Map<String, String> result = langValues(model.getResource(res.newConceptIRI), SKOS.prefLabel);
        assertEquals("Bez jazyka", result.get(DEFAULT_LANG), "blank tag stored under DEFAULT_LANG");
        assertEquals("Seed", result.get("xx"), "the non-default seed survives the merge");
    }
}
