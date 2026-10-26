package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.concept.AltNameModel;
import com.dia.ismdtoolbackend.models.concept.DefinitionModel;
import com.dia.ismdtoolbackend.testsupport.fixtures.Texts;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.dia.constants.VocabularyConstants.DEFAULT_NS;
import static org.assertj.core.api.Assertions.assertThat;

/** Edits of names, descriptions, definitions and alternative names through {@link ConceptEditor}. */
class ConceptEditorLabelsTest extends ConceptEditorTestBase {

    private static final String CONCEPT = DEFAULT_NS + "pojem/třída";

    private void seed(String extraPredicates) {
        Ttl.add(model, "<" + CONCEPT + "> a ofn:třída ; skos:prefLabel \"Třída\"@cs " + extraPredicates + " .");
    }

    private ConceptEditor.EditResult edit() {
        return conceptEditor.editConcept(CONCEPT, classConceptEditModel, model, null);
    }

    private void assertModelIs(String predicates) {
        Model expected = Ttl.parse("<" + CONCEPT + "> a ofn:třída ; skos:prefLabel \"Třída\"@cs " + predicates + " .");
        assertThat(model.isIsomorphicWith(expected))
                .as("missing: %s%nextra: %s", expected.difference(model), model.difference(expected))
                .isTrue();
    }

    private static Map<String, String> byLang(String... langValuePairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < langValuePairs.length; i += 2) {
            map.put(langValuePairs[i], langValuePairs[i + 1]);
        }
        return map;
    }

    @Test
    void nameWithAnEmptyMapOrNullMapChangesNothing() {
        seed("");
        classConceptEditModel.setNameModel(Texts.name(Map.of()));
        assertThat(edit().changesCount).isZero();

        classConceptEditModel.setNameModel(new NameModel());
        assertThat(edit().changesCount).isZero();
    }

    @Test
    void blankNameValueDropsThatLanguageOnly() {
        seed("; skos:prefLabel \"Class\"@en");
        classConceptEditModel.setNameModel(Texts.name(byLang("cs", "Třída", "en", " ")));

        ConceptEditor.EditResult result = edit();

        assertThat(result.iriChanged).isFalse();
        assertModelIs("");
    }

    @Test
    void englishNameIsAddedWithoutARename() {
        seed("");
        classConceptEditModel.setNameModel(Texts.name(byLang("cs", "Třída", "en", " Class ")));

        assertThat(edit().iriChanged).isFalse();
        assertModelIs("; skos:prefLabel \"Class\"@en");
    }

    @Test
    void emptyOrNullDescriptionAndDefinitionMapsClearThem() {
        seed("; dct:description \"Popis\"@cs ; skos:definition \"Definice\"@cs");
        classConceptEditModel.setDescriptionModel(Texts.description(Map.of()));
        classConceptEditModel.setDefinitionModel(new DefinitionModel());

        edit();

        assertModelIs("");
    }

    @Test
    void clearingAbsentDescriptionAndDefinitionStagesNothing() {
        seed("");
        classConceptEditModel.setDescriptionModel(new DescriptionModel());
        classConceptEditModel.setDefinitionModel(Texts.definition(Map.of()));

        assertThat(edit().changesCount).isZero();
    }

    @Test
    void descriptionAndDefinitionMergePerLanguage() {
        seed("; dct:description \"Popis\"@cs, \"Description\"@en ; skos:definition \"Definice\"@cs");
        classConceptEditModel.setDescriptionModel(Texts.description(byLang("en", "", "de", "Beschreibung")));
        classConceptEditModel.setDefinitionModel(Texts.definition(byLang("en", "Definition")));

        edit();

        assertModelIs("""
                ; dct:description "Popis"@cs, "Beschreibung"@de
                ; skos:definition "Definice"@cs, "Definition"@en
                """);
    }

    @Test
    void altNamesAreReplacedAsAWholeSkippingBlanksAndNullLists() {
        seed("; skos:altLabel \"Starý\"@cs");
        Map<String, List<String>> altNames = new LinkedHashMap<>();
        altNames.put("cs", Arrays.asList("Druhý", " ", null, "První"));
        altNames.put(" ", List.of("Bez jazyka"));
        altNames.put("en", null);
        classConceptEditModel.setAltNameModel(Texts.altNames(altNames));

        edit();

        assertModelIs("; skos:altLabel \"Druhý\"@cs, \"První\"@cs, \"Bez jazyka\"@cs");
    }

    @Test
    void sameAltNamesInAnotherOrderStageNothing() {
        seed("; skos:altLabel \"A\"@cs, \"B\"@cs");
        classConceptEditModel.setAltNameModel(Texts.altName("cs", "B", "A"));

        assertThat(edit().changesCount).isZero();
    }

    @Test
    void emptyOrNullAltNameMapClearsThem() {
        seed("; skos:altLabel \"A\"@cs");
        classConceptEditModel.setAltNameModel(new AltNameModel());

        edit();

        assertModelIs("");
    }

    @Test
    void untaggedLiteralsAreReadAsCzech() {
        seed("; skos:altLabel \"A\" ; dct:description \"Popis\"");
        classConceptEditModel.setAltNameModel(Texts.altName("cs", "A"));
        classConceptEditModel.setDescriptionModel(Texts.description("cs", "Popis"));

        assertThat(edit().changesCount).isZero();
    }

    @Test
    void nonLiteralLabelValuesAreIgnoredWhenReading() {
        seed("; skos:altLabel <https://example.org/x> ; dct:description <https://example.org/y>");
        classConceptEditModel.setAltNameModel(Texts.altNames(new LinkedHashMap<>(Map.of("cs", new ArrayList<>()))));

        assertThat(edit().changesCount).isZero();
    }
}
