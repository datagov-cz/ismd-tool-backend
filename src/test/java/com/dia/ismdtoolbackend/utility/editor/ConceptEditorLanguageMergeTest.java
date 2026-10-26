package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.concept.AltNameModel;
import com.dia.ismdtoolbackend.models.concept.DefinitionModel;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static com.dia.constants.VocabularyConstants.DEFAULT_NS;
import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;

/**
 * Language-map handling of the concept editor:
 *
 *   - prefLabel / description / definition  → MERGE by language tag
 *     (a single-language edit keeps the other languages)
 *   - altLabel                              → FULL REPLACE
 *     (a single-language edit drops the other languages)
 *   - empty-string value for a tag          → deletes just that tag
 *   - blank / missing tag                   → falls back to DEFAULT_LANG
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
        classConceptEditModel.setNameModel(newName);

        ConceptEditor.EditResult res = conceptEditor.editConcept(iri, classConceptEditModel, model, null);

        Map<String, String> result = langValues(model.getResource(res.newConceptIRI), SKOS.prefLabel);
        assertThat(result.get("cs")).as("cs should be updated").isEqualTo("Nové cs");
        assertThat(result.get("en")).as("en must survive the merge").isEqualTo("en value");
        assertThat(result).hasSize(2);
    }

    // description MERGE
    @Test
    void description_isMergedByLanguage_notReplaced() {
        String iri = DEFAULT_NS + "merge-desc";
        seedBilingual(iri, DC_DESCRIPTION);

        DescriptionModel newDesc = new DescriptionModel();
        newDesc.setDescription(Map.of("cs", "Nový popis"));

        stubCommonNull();
        classConceptEditModel.setDescriptionModel(newDesc);

        conceptEditor.editConcept(iri, classConceptEditModel, model, null);

        Map<String, String> result = langValues(model.getResource(iri), DC_DESCRIPTION);
        assertThat(result.get("cs")).isEqualTo("Nový popis");
        assertThat(result.get("en")).as("en description must survive the merge").isEqualTo("en value");
    }

    // definition MERGE
    @Test
    void definition_isMergedByLanguage_notReplaced() {
        String iri = DEFAULT_NS + "merge-def";
        seedBilingual(iri, SKOS.definition);

        DefinitionModel newDef = new DefinitionModel();
        newDef.setDefinition(Map.of("cs", "Nová definice"));

        stubCommonNull();
        classConceptEditModel.setDefinitionModel(newDef);

        conceptEditor.editConcept(iri, classConceptEditModel, model, null);

        Map<String, String> result = langValues(model.getResource(iri), SKOS.definition);
        assertThat(result.get("cs")).isEqualTo("Nová definice");
        assertThat(result.get("en")).as("en definition must survive the merge").isEqualTo("en value");
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
        classConceptEditModel.setAltNameModel(newAlt);

        conceptEditor.editConcept(iri, classConceptEditModel, model, null);

        Map<String, String> result = langValues(model.getResource(iri), SKOS.altLabel);
        assertThat(result.get("cs")).isEqualTo("Nový alt");
        assertThat(result.get("en")).as("en altLabel must be dropped — altLabel is full-replace").isNull();
        assertThat(result).hasSize(1);
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
        classConceptEditModel.setAltNameModel(newAlt);

        conceptEditor.editConcept(iri, classConceptEditModel, model, null);

        assertThat(allLangValues(model.getResource(iri), SKOS.altLabel).get("cs"))
                .as("both cs alt labels must be written — the old Map<String,String> kept only the last")
                .isEqualTo(List.of("Municipalita", "Obec"));
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
        classConceptEditModel.setAltNameModel(sameAlt);

        conceptEditor.editConcept(iri, classConceptEditModel, model, null);

        assertThat(allLangValues(model.getResource(iri), SKOS.altLabel).get("cs"))
                .isEqualTo(List.of("Municipalita", "Obec"));
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
        classConceptEditModel.setNameModel(newName);

        conceptEditor.editConcept(iri, classConceptEditModel, model, null);

        Map<String, String> result = langValues(model.getResource(iri), SKOS.prefLabel);
        assertThat(result.get("cs")).as("cs must remain").isEqualTo("cs hodnota");
        assertThat(result.get("en")).as("en cleared via empty string").isNull();
        assertThat(result).hasSize(1);
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
        classConceptEditModel.setNameModel(newName);

        // Adding a label under DEFAULT_LANG changes the name → IRI rename; read from new IRI.
        ConceptEditor.EditResult res = conceptEditor.editConcept(iri, classConceptEditModel, model, null);

        Map<String, String> result = langValues(model.getResource(res.newConceptIRI), SKOS.prefLabel);
        assertThat(result.get(DEFAULT_LANG)).as("blank tag stored under DEFAULT_LANG").isEqualTo("Bez jazyka");
        assertThat(result.get("xx")).as("the non-default seed survives the merge").isEqualTo("Seed");
    }
}
