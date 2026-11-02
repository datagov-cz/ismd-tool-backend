package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.models.concept.DigitalObjectModel;
import com.dia.ismdtoolbackend.testsupport.fixtures.Texts;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.testsupport.rdf.Ttl.iris;
import static com.dia.ismdtoolbackend.testsupport.rdf.Ttl.literals;
import static com.dia.ismdtoolbackend.testsupport.rdf.Ttl.types;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Edits of class concepts (TRIDA) through {@link ConceptEditor}. */
class ConceptEditorClassConceptTest extends ConceptEditorTestBase {

    private static final String CONCEPT = DEFAULT_NS + "pojem/třída";
    private static final String ELI_2021_12 = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2021/12";
    private static final String ELI_2022_100 = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2022/100";
    private static final String ELI_2023_50 = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2023/50";
    private static final String SHARING_ITEMS = "https://data.dia.gov.cz/zdroj/číselníky/způsoby-sdílení-údajů/položky/";
    private static final String ACQUISITION_ITEMS = "https://data.dia.gov.cz/zdroj/číselníky/způsoby-získání-údajů/položky/";
    private static final String CONTENT_TYPE_ITEMS = "https://data.dia.gov.cz/zdroj/číselníky/typy-obsahu-údajů/položky/";

    private static final Property DESCRIPTION = Ttl.property(DCT_NS + "description");
    private static final Property HIERARCHY = Ttl.property(DEFAULT_NS + "nadřazená-třída");
    private static final Property DEFINING_LEGAL = Ttl.property(OFN_NAMESPACE + DEFINUJICI_USTANOVENI);
    private static final Property RELATED_LEGAL = Ttl.property(OFN_NAMESPACE + SOUVISEJICI_USTANOVENI);
    private static final Property DEFINING_NON_LEGAL = Ttl.property(DEFAULT_NS + DEFINUJICI_NELEGISLATIVNI_ZDROJ);
    private static final Property RELATED_NON_LEGAL = Ttl.property(DEFAULT_NS + SOUVISEJICI_NELEGISLATIVNI_ZDROJ);
    private static final Property PROVISION = Ttl.property(OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST);
    private static final Property SHARING = Ttl.property(OFN_NAMESPACE + ZPUSOB_SDILENI);
    private static final Property ACQUISITION = Ttl.property(OFN_NAMESPACE + ZPUSOB_ZISKANI);
    private static final Property CONTENT_TYPE = Ttl.property(OFN_NAMESPACE + TYP_OBSAHU);
    private static final Property AGENDA = Ttl.property(DEFAULT_NS + AGENDOVY_104 + AGENDA_LONG);
    private static final Property EXACT_MATCH = SKOS.exactMatch;
    private static final Property IN_TEZAURUS = Ttl.property(DEFAULT_NS + "inTezaurus");

    /** Seeds {@link #CONCEPT} as a class labelled "Třída" plus the given Turtle predicate-object list. */
    private void seedClass(String extraPredicates) {
        Ttl.add(model, "<" + CONCEPT + "> a ofn:třída ; skos:prefLabel \"Třída\"@cs " + extraPredicates + " .");
    }

    private ConceptEditor.EditResult edit() {
        return conceptEditor.editConcept(CONCEPT, classConceptEditModel, model, null);
    }

    private Model snapshot() {
        return ModelFactory.createDefaultModel().add(model);
    }

    @Nested
    class Rename {

        @Test
        void newNameMovesEveryTripleToTheIriDerivedFromTheIdentifier() {
            String oldIri = "https://slovnik.gov.cz/pojem/old-class";
            Ttl.add(model, "<" + oldIri + "> a skos:Concept ; skos:prefLabel \"Old name\"@cs .");
            classConceptEditModel.setNameModel(Texts.name("cs", "New name"));
            classConceptEditModel.setIdentifier("ID-1");

            ConceptEditor.EditResult result = conceptEditor.editConcept(oldIri, classConceptEditModel, model, null);

            String newIri = DEFAULT_NS + "pojem/id-1";
            assertThat(result.iriChanged).isTrue();
            assertThat(result.newConceptIRI).isEqualTo(newIri);
            assertThat(model.isIsomorphicWith(Ttl.parse(
                    "<" + newIri + "> a skos:Concept ; skos:prefLabel \"New name\"@cs ."))).isTrue();
        }

        @Test
        void incomingReferencesFollowTheRename() {
            String oldIri = DEFAULT_NS + "pojem/stará-třída";
            Ttl.add(model, """
                    <%s> a ofn:třída ; skos:prefLabel "Stará třída"@cs .
                    <%s> rdfs:domain <%s> .
                    """.formatted(oldIri, DEFAULT_NS + "pojem/vlastnost", oldIri));
            classConceptEditModel.setNameModel(Texts.name("cs", "Nová třída"));

            ConceptEditor.EditResult result = conceptEditor.editConcept(oldIri, classConceptEditModel, model, null);

            assertThat(result.newConceptIRI).isEqualTo(DEFAULT_NS + "pojem/nová-třída");
            assertThat(iris(model, DEFAULT_NS + "pojem/vlastnost", RDFS.domain))
                    .containsExactly(result.newConceptIRI);
            assertThat(model.containsResource(model.getResource(oldIri))).isFalse();
        }

        @Test
        void fieldsEditedInTheSameRequestReplaceTheCopiedValues() {
            String oldIri = DEFAULT_NS + "pojem/stará-třída";
            Ttl.add(model, """
                    <%s> a ofn:třída, ofn:typ-objektu-práva, l111:veřejný-údaj ;
                        skos:prefLabel "Stará třída"@cs ;
                        rdfs:subClassOf <https://example.com/old-broader> .
                    """.formatted(oldIri));
            classConceptEditModel.setNameModel(Texts.name("cs", "Nová třída"));
            classConceptEditModel.setType("subjekt");
            classConceptEditModel.setIsPublic(Boolean.FALSE);
            classConceptEditModel.setBroaderConcept(List.of("https://example.com/new-broader"));

            ConceptEditor.EditResult result = conceptEditor.editConcept(oldIri, classConceptEditModel, model, null);

            String newIri = result.newConceptIRI;
            assertThat(types(model, newIri)).containsExactlyInAnyOrder(OFN_NAMESPACE + TRIDA, OFN_NAMESPACE + TSP);
            assertThat(iris(model, newIri, RDFS.subClassOf)).containsExactly("https://example.com/new-broader");
            assertThat(iris(model, newIri, HIERARCHY)).containsExactly("https://example.com/new-broader");
            assertThat(model.containsResource(model.getResource(oldIri))).isFalse();
        }

        @Test
        void unchangedNameKeepsTheIri() {
            seedClass("");
            classConceptEditModel.setNameModel(Texts.name("cs", "Třída"));

            ConceptEditor.EditResult result = edit();

            assertThat(result.iriChanged).isFalse();
            assertThat(result.newConceptIRI).isEqualTo(CONCEPT);
            assertThat(result.changesCount).isZero();
        }
    }

    @Nested
    class ClassSpecificFields {

        @Test
        void typeGovernanceAndHierarchyAreReplaced() {
            seedClass("""
                    ; a ofn:typ-objektu-práva
                    ; a104:%s "old-agenda"
                    ; ofn:%s <%sold>
                    ; rdfs:subClassOf <https://example.com/old-broader>
                    ; sl:nadřazená-třída <https://example.com/old-broader>
                    """.formatted(AGENDA_LONG, ZPUSOB_SDILENI, SHARING_ITEMS));
            classConceptEditModel.setType("subjekt");
            classConceptEditModel.setAgendaCode("");
            classConceptEditModel.setContentType("provozní");
            classConceptEditModel.setAcquisitionMethod("vlastní");
            classConceptEditModel.setSharingMethod(List.of("nesdílené"));
            classConceptEditModel.setBroaderConcept(List.of("https://example.com/new-broader"));

            ConceptEditor.EditResult result = edit();

            assertThat(result.iriChanged).isFalse();
            assertThat(types(model, CONCEPT)).containsExactlyInAnyOrder(OFN_NAMESPACE + TRIDA, OFN_NAMESPACE + TSP);
            assertThat(model.getResource(CONCEPT).hasProperty(AGENDA)).isFalse();
            assertThat(iris(model, CONCEPT, SHARING)).containsExactly(SHARING_ITEMS + "nesdílené");
            assertThat(iris(model, CONCEPT, ACQUISITION)).containsExactly(ACQUISITION_ITEMS + "vlastní");
            assertThat(iris(model, CONCEPT, CONTENT_TYPE)).containsExactly(CONTENT_TYPE_ITEMS + "provozní");
            assertThat(iris(model, CONCEPT, RDFS.subClassOf)).containsExactly("https://example.com/new-broader");
            assertThat(iris(model, CONCEPT, HIERARCHY)).containsExactly("https://example.com/new-broader");
        }

        @Test
        void sharingMethodsKeepEveryValue() {
            seedClass("");
            classConceptEditModel.setSharingMethod(List.of("nesdílené", "veřejně přístupné"));

            edit();

            assertThat(iris(model, CONCEPT, SHARING))
                    .containsExactlyInAnyOrder(SHARING_ITEMS + "nesdílené", SHARING_ITEMS + "veřejně-přístupné");
        }

        @Test
        void inTezaurusIsWrittenAsALiteral() {
            seedClass("");
            classConceptEditModel.setInTezaurus(Boolean.TRUE);

            edit();

            assertThat(literals(model, CONCEPT, IN_TEZAURUS)).containsExactly("true");
        }
    }

    @Nested
    class Sources {

        @Test
        void legalSourcesAreReplaced() {
            seedClass("""
                    ; ofn:%s <https://old.example.com/eli/cz/act/2000/1>
                    ; ofn:%s <https://old.example.com/eli/cz/act/2000/2>
                    """.formatted(DEFINUJICI_USTANOVENI, SOUVISEJICI_USTANOVENI));
            classConceptEditModel.setDefiningLegalSource(List.of(ELI_2021_12));
            classConceptEditModel.setRelatedLegalSource(List.of(ELI_2022_100));

            edit();

            assertThat(iris(model, CONCEPT, DEFINING_LEGAL)).containsExactly(ELI_2021_12);
            assertThat(iris(model, CONCEPT, RELATED_LEGAL)).containsExactly(ELI_2022_100);
        }

        @Test
        void emptyLegalSourceListsRemoveTheSources() {
            seedClass("""
                    ; ofn:%s <%s>
                    ; ofn:%s <%s>
                    """.formatted(DEFINUJICI_USTANOVENI, ELI_2021_12, SOUVISEJICI_USTANOVENI, ELI_2022_100));
            classConceptEditModel.setDefiningLegalSource(List.of());
            classConceptEditModel.setRelatedLegalSource(List.of());

            edit();

            assertThat(model.getResource(CONCEPT).hasProperty(DEFINING_LEGAL)).isFalse();
            assertThat(model.getResource(CONCEPT).hasProperty(RELATED_LEGAL)).isFalse();
        }

        @Test
        void nonLegalSourcesAreWrittenAsDigitalObjects() {
            seedClass("");
            classConceptEditModel.setDefiningNonLegalSource(
                    List.of(new DigitalObjectModel("Doc 1", "Popis 1", "https://example.com/doc1")));
            classConceptEditModel.setRelatedNonLegalSource(
                    List.of(new DigitalObjectModel("Doc 2", "Popis 2", "https://example.com/doc2")));

            edit();

            Model expected = Ttl.parse("""
                    <%s> a ofn:třída ; skos:prefLabel "Třída"@cs ;
                        sl:%s [ a <%s> ; <%s> <https://example.com/doc1> ;
                                dct:title "Doc 1"@cs ; dct:description "Popis 1"@cs ] ;
                        sl:%s [ a <%s> ; <%s> <https://example.com/doc2> ;
                                dct:title "Doc 2"@cs ; dct:description "Popis 2"@cs ] .
                    """.formatted(CONCEPT, DEFINUJICI_NELEGISLATIVNI_ZDROJ, DIGITALNI_OBJEKT, SCHEMA_URL,
                    SOUVISEJICI_NELEGISLATIVNI_ZDROJ, DIGITALNI_OBJEKT, SCHEMA_URL));
            assertThat(model.isIsomorphicWith(expected)).isTrue();
        }

        @Test
        void unchangedNonLegalSourcesAreNotAChange() {
            seedClass("""
                    ; sl:%s [ a <%s> ; <%s> <https://example.com/doc1> ;
                              dct:title "Doc 1"@cs ; dct:description "Popis 1"@cs ]
                    """.formatted(DEFINUJICI_NELEGISLATIVNI_ZDROJ, DIGITALNI_OBJEKT, SCHEMA_URL));
            Model before = snapshot();
            classConceptEditModel.setNameModel(Texts.name("cs", "Třída"));
            classConceptEditModel.setDefiningNonLegalSource(
                    List.of(new DigitalObjectModel("Doc 1", "Popis 1", "https://example.com/doc1")));
            classConceptEditModel.setRelatedNonLegalSource(List.of());

            ConceptEditor.EditResult result = edit();

            assertThat(result.changesCount).isZero();
            assertThat(result.statementsToRemove).isEmpty();
            assertThat(result.statementsToAdd).isEmpty();
            assertThat(model.isIsomorphicWith(before)).isTrue();
        }

        @Test
        void replacedNonLegalSourceLeavesNothingOfTheOldDocument() {
            seedClass("""
                    ; sl:%s [ a <%s> ; <%s> <https://example.com/doc1> ;
                              dct:title "Doc 1"@cs ; dct:description "Popis 1"@cs ]
                    """.formatted(DEFINUJICI_NELEGISLATIVNI_ZDROJ, DIGITALNI_OBJEKT, SCHEMA_URL));
            classConceptEditModel.setDefiningNonLegalSource(
                    List.of(new DigitalObjectModel("Doc 2", null, "https://example.com/doc2")));

            edit();

            Model expected = Ttl.parse("""
                    <%s> a ofn:třída ; skos:prefLabel "Třída"@cs ;
                        sl:%s [ a <%s> ; <%s> <https://example.com/doc2> ; dct:title "Doc 2"@cs ] .
                    """.formatted(CONCEPT, DEFINUJICI_NELEGISLATIVNI_ZDROJ, DIGITALNI_OBJEKT, SCHEMA_URL));
            assertThat(model.isIsomorphicWith(expected)).isTrue();
        }

        @Test
        void invalidNonLegalUrlsRejectTheWholeEditAndNameEveryField() {
            seedClass("""
                    ; sl:%s sl:digitální-dokument-1
                    ; sl:%s sl:digitální-dokument-2
                    """.formatted(DEFINUJICI_NELEGISLATIVNI_ZDROJ, SOUVISEJICI_NELEGISLATIVNI_ZDROJ));
            Model before = snapshot();
            classConceptEditModel.setDefiningNonLegalSource(List.of(new DigitalObjectModel("Bez url", "Popis", "")));
            classConceptEditModel.setRelatedNonLegalSource(
                    List.of(new DigitalObjectModel("Špatná url", "Popis", "not a url")));

            assertThatThrownBy(ConceptEditorClassConceptTest.this::edit)
                    .isExactlyInstanceOf(ConceptValidationException.class)
                    .hasMessageStartingWith("Neplatné hodnoty v úpravě pojmu: ")
                    .hasMessageContaining("definingNonLegalSource")
                    .hasMessageContaining("relatedNonLegalSource");
            assertThat(model.isIsomorphicWith(before)).isTrue();
        }
    }

    @Nested
    class TextFields {

        @Test
        void nameDescriptionDefinitionAndAltNameAreReplaced() {
            seedClass("""
                    ; dct:description "Old description"@cs
                    ; skos:definition "Old definition"@cs
                    ; skos:altLabel "Old alt"@cs
                    """);
            classConceptEditModel.setNameModel(Texts.name("cs", "Nová třída"));
            classConceptEditModel.setDescriptionModel(Texts.description("cs", "New description"));
            classConceptEditModel.setDefinitionModel(Texts.definition("cs", "New definition"));
            classConceptEditModel.setAltNameModel(Texts.altName("cs", "New alt"));

            ConceptEditor.EditResult result = edit();

            assertThat(model.isIsomorphicWith(Ttl.parse("""
                    <%s> a ofn:třída ; skos:prefLabel "Nová třída"@cs ;
                        dct:description "New description"@cs ;
                        skos:definition "New definition"@cs ;
                        skos:altLabel "New alt"@cs .
                    """.formatted(result.newConceptIRI)))).isTrue();
            assertThat(result.newConceptIRI).isEqualTo(DEFAULT_NS + "pojem/nová-třída");
        }

        @Test
        void blankDescriptionAndDefinitionClearThemAndLeaveAltNamesAlone() {
            seedClass("""
                    ; dct:description "Description"@cs
                    ; skos:definition "Definition"@cs
                    ; skos:altLabel "Alt"@cs
                    """);
            classConceptEditModel.setDescriptionModel(Texts.description("cs", ""));
            classConceptEditModel.setDefinitionModel(Texts.definition("cs", " "));

            edit();

            assertThat(model.getResource(CONCEPT).hasProperty(DESCRIPTION)).isFalse();
            assertThat(model.getResource(CONCEPT).hasProperty(SKOS.definition)).isFalse();
            assertThat(literals(model, CONCEPT, SKOS.altLabel)).containsExactly("Alt@cs");
        }
    }

    @Nested
    class ExactMatch {

        @Test
        void listReplacesTheMatchesAndSkipsBlanks() {
            seedClass("; skos:exactMatch <https://example.com/old-match>");
            classConceptEditModel.setExactMatch(
                    List.of("https://example.com/new-uri-1", " ", "https://example.com/new-uri-2"));

            edit();

            assertThat(iris(model, CONCEPT, EXACT_MATCH))
                    .containsExactlyInAnyOrder("https://example.com/new-uri-1", "https://example.com/new-uri-2");
        }

        @Test
        void emptyListClearsTheMatches() {
            seedClass("; skos:exactMatch <https://example.com/old-match>");
            classConceptEditModel.setExactMatch(List.of());

            edit();

            assertThat(model.getResource(CONCEPT).hasProperty(EXACT_MATCH)).isFalse();
        }
    }

    @Nested
    class PrivacyAndClassification {

        @Test
        void provisionIsStoredVerbatim() {
            seedClass("");
            classConceptEditModel.setPrivacyProvisions(List.of(ELI_2023_50));

            edit();

            assertThat(iris(model, CONCEPT, PROVISION)).containsExactly(ELI_2023_50);
        }

        @Test
        void blankProvisionClearsTheProvisions() {
            seedClass("; l111:%s <%s>".formatted(USTANOVENI_NEVEREJNOST, ELI_2023_50));
            classConceptEditModel.setPrivacyProvisions(List.of(" "));

            edit();

            assertThat(model.getResource(CONCEPT).hasProperty(PROVISION)).isFalse();
        }

        @Test
        void publicWithoutProvisionsIsVerejnyUdaj() {
            seedClass("");
            classConceptEditModel.setIsPublic(Boolean.TRUE);

            edit();

            assertThat(types(model, CONCEPT))
                    .containsExactlyInAnyOrder(OFN_NAMESPACE + TRIDA, OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ);
        }

        @Test
        void nonPublicWithAProvisionIsNeverejnyUdaj() {
            seedClass("");
            classConceptEditModel.setIsPublic(Boolean.FALSE);
            classConceptEditModel.setPrivacyProvisions(List.of(ELI_2023_50));

            edit();

            assertThat(types(model, CONCEPT))
                    .containsExactlyInAnyOrder(OFN_NAMESPACE + TRIDA, OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ);
        }

        @Test
        void nonPublicWithoutAProvisionDropsTheClassification() {
            seedClass("; a l111:veřejný-údaj");
            classConceptEditModel.setIsPublic(Boolean.FALSE);

            edit();

            assertThat(types(model, CONCEPT)).containsExactly(OFN_NAMESPACE + TRIDA);
        }

        @Test
        void publicWithAProvisionIsRejectedWithoutAWrite() {
            seedClass("");
            Model before = snapshot();
            classConceptEditModel.setIsPublic(Boolean.TRUE);
            classConceptEditModel.setPrivacyProvisions(List.of(ELI_2023_50));

            assertThatThrownBy(ConceptEditorClassConceptTest.this::edit)
                    .isExactlyInstanceOf(ConceptValidationException.class)
                    .hasMessageContaining("nemůže být současně veřejn");
            assertThat(model.isIsomorphicWith(before)).isTrue();
        }

        @Test
        void renameWithTypeChangeAndNonPublicDropsTheOldClassification() {
            String oldIri = DEFAULT_NS + "pojem/stará";
            Ttl.add(model, """
                    <%s> a ofn:třída, ofn:typ-objektu-práva, l111:veřejný-údaj ; skos:prefLabel "Stará"@cs .
                    """.formatted(oldIri));
            classConceptEditModel.setNameModel(Texts.name("cs", "Nová"));
            classConceptEditModel.setType("subjekt");
            classConceptEditModel.setIsPublic(Boolean.FALSE);

            ConceptEditor.EditResult result = conceptEditor.editConcept(oldIri, classConceptEditModel, model, null);

            assertThat(types(model, result.newConceptIRI))
                    .containsExactlyInAnyOrder(OFN_NAMESPACE + TRIDA, OFN_NAMESPACE + TSP);
        }
    }

    @Test
    void missingConceptIsRejected() {
        assertThatThrownBy(ConceptEditorClassConceptTest.this::edit)
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Concept with IRI " + CONCEPT + " not found in the model");
    }
}
