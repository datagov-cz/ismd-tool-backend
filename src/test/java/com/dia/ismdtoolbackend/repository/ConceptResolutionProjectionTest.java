package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Label, scheme and domain/range projection rules of {@link ConceptResolutionProjection}. */
class ConceptResolutionProjectionTest {

    private static final String OSOBA = Ttl.POJEM + "osoba";
    private static final String ADRESA = Ttl.POJEM + "adresa";
    private static final String BYDLI = Ttl.POJEM + "bydlí-v";
    private static final String OTHER_SCHEME = "https://slovník.gov.cz/datový/jiný-slovník/";

    @Nested
    @DisplayName("projectResolutions")
    class SchemeDriven {

        @Test
        @DisplayName("null or empty model yields an empty map")
        void nullOrEmptyModel() {
            assertThat(ConceptResolutionProjection.projectResolutions(null, SearchSource.ISMD)).isEmpty();
            assertThat(ConceptResolutionProjection.projectResolutions(
                    ModelFactory.createDefaultModel(), SearchSource.ISMD)).isEmpty();
        }

        @Test
        @DisplayName("blank-node subjects and literal schemes are skipped")
        void skipsNonIriInSchemeEnds() {
            Model m = Ttl.parse("""
                    [] skos:inScheme ex: ; skos:prefLabel "Anonym"@cs .
                    pojem:adresa skos:inScheme "not-a-scheme" .
                    pojem:osoba skos:inScheme ex: .
                    """);

            Map<String, ResolvedConceptDto> out = ConceptResolutionProjection.projectResolutions(m, SearchSource.NKD);

            assertThat(out).containsOnlyKeys(OSOBA);
            assertThat(out.get(OSOBA).ontologyIri()).isEqualTo(Ttl.VOCABULARY);
            assertThat(out.get(OSOBA).source()).isEqualTo(SearchSource.NKD);
        }

        @Test
        @DisplayName("a concept in two schemes is projected once")
        void conceptInTwoSchemesProjectedOnce() {
            Model m = Ttl.parse("""
                    pojem:osoba skos:inScheme ex:, <%s> .
                    """.formatted(OTHER_SCHEME));

            Map<String, ResolvedConceptDto> out = ConceptResolutionProjection.projectResolutions(m, SearchSource.ISMD);

            assertThat(out).containsOnlyKeys(OSOBA);
            assertThat(out.get(OSOBA).ontologyIri()).isIn(Ttl.VOCABULARY, OTHER_SCHEME);
        }

        @Test
        @DisplayName("labels: untagged keyed by \"\", first per language wins, empty and IRI values dropped")
        void multilingualLabelRules() {
            Model m = Ttl.parse("""
                    pojem:osoba skos:inScheme ex: ;
                        skos:prefLabel "Osoba"@cs, ""@en, "Person", pojem:adresa .
                    ex: skos:prefLabel "Testovací slovník"@cs .
                    """);

            ResolvedConceptDto dto = ConceptResolutionProjection.projectResolutions(m, SearchSource.ISMD).get(OSOBA);

            assertThat(dto.conceptName()).containsOnly(
                    Map.entry("cs", "Osoba"), Map.entry("", "Person"));
            assertThat(dto.ontologyName()).containsOnly(Map.entry("cs", "Testovací slovník"));
        }

        @Test
        @DisplayName("first value per language wins")
        void firstValuePerLanguageWins() {
            Model m = Ttl.parse("""
                    pojem:osoba skos:inScheme ex: ; skos:prefLabel "Osoba"@cs, "Člověk"@cs .
                    """);

            ResolvedConceptDto dto = ConceptResolutionProjection.projectResolutions(m, SearchSource.ISMD).get(OSOBA);

            assertThat(dto.conceptName()).hasSize(1).containsKey("cs");
        }

        @Test
        @DisplayName("no labels anywhere leaves both name maps null")
        void noLabelsLeavesNamesNull() {
            Model m = Ttl.parse("pojem:osoba skos:inScheme ex: .");

            ResolvedConceptDto dto = ConceptResolutionProjection.projectResolutions(m, SearchSource.ISMD).get(OSOBA);

            assertThat(dto.conceptName()).isNull();
            assertThat(dto.ontologyName()).isNull();
            assertThat(dto.resolvedDomain()).isNull();
        }

        @Test
        @DisplayName("blank-node types do not make a relationship; literal domain yields no stub")
        void blankTypeAndLiteralDomain() {
            Model m = Ttl.parse("""
                    pojem:osoba skos:inScheme ex: ; a [] ; rdfs:domain pojem:adresa .
                    pojem:bydlí-v skos:inScheme ex: ; a [], owl:ObjectProperty ;
                        rdfs:domain "osoba" ; rdfs:range pojem:adresa .
                    """);

            Map<String, ResolvedConceptDto> out = ConceptResolutionProjection.projectResolutions(m, SearchSource.ISMD);

            assertThat(out.get(OSOBA).resolvedDomain()).isNull();
            assertThat(out.get(BYDLI).resolvedDomain()).isNull();
            assertThat(out.get(BYDLI).resolvedRange().iri()).isEqualTo(ADRESA);
        }
    }

    @Nested
    @DisplayName("projectDisplayResolutions")
    class RequestDriven {

        @Test
        @DisplayName("null model, empty model or null IRI list yields an empty map")
        void guardInputs() {
            Model m = Ttl.parse("pojem:osoba a skos:Concept .");
            assertThat(ConceptResolutionProjection.projectDisplayResolutions(null, SearchSource.NKD, List.of(OSOBA)))
                    .isEmpty();
            assertThat(ConceptResolutionProjection.projectDisplayResolutions(
                    ModelFactory.createDefaultModel(), SearchSource.NKD, List.of(OSOBA))).isEmpty();
            assertThat(ConceptResolutionProjection.projectDisplayResolutions(m, SearchSource.NKD, null)).isEmpty();
        }

        @Test
        @DisplayName("a requested IRI without rdf:type is absent")
        void untypedIriIsAbsent() {
            Model m = Ttl.parse("""
                    pojem:osoba a skos:Concept .
                    pojem:adresa skos:prefLabel "Adresa"@cs .
                    """);

            Map<String, ResolvedConceptDto> out =
                    ConceptResolutionProjection.projectDisplayResolutions(m, SearchSource.NKD, List.of(OSOBA, ADRESA));

            assertThat(out).containsOnlyKeys(OSOBA);
        }

        @Test
        @DisplayName("a concept without a scheme still carries its label; ontology fields stay null")
        void schemelessConceptKeepsLabel() {
            Model m = Ttl.parse("pojem:osoba a skos:Concept ; skos:prefLabel \"Osoba\"@cs .");

            ResolvedConceptDto dto = ConceptResolutionProjection
                    .projectDisplayResolutions(m, SearchSource.NKD, List.of(OSOBA)).get(OSOBA);

            assertThat(dto.conceptName()).containsOnly(Map.entry("cs", "Osoba"));
            assertThat(dto.ontologyIri()).isNull();
            assertThat(dto.ontologyName()).isNull();
            assertThat(dto.source()).isEqualTo(SearchSource.NKD);
        }

        @Test
        @DisplayName("a literal scheme is ignored rather than projected")
        void literalSchemeIgnored() {
            Model m = Ttl.parse("pojem:osoba a skos:Concept ; skos:inScheme \"ex\" .");

            ResolvedConceptDto dto = ConceptResolutionProjection
                    .projectDisplayResolutions(m, SearchSource.NKD, List.of(OSOBA)).get(OSOBA);

            assertThat(dto.ontologyIri()).isNull();
            assertThat(dto.conceptName()).isNull();
        }

        @Test
        @DisplayName("an IRI scheme projects its IRI and label")
        void iriSchemeProjected() {
            Model m = Ttl.parse("""
                    pojem:osoba a skos:Concept ; skos:inScheme ex: .
                    ex: skos:prefLabel "Testovací slovník"@cs .
                    """);

            ResolvedConceptDto dto = ConceptResolutionProjection
                    .projectDisplayResolutions(m, SearchSource.NKD, List.of(OSOBA)).get(OSOBA);

            assertThat(dto.ontologyIri()).isEqualTo(Ttl.VOCABULARY);
            assertThat(dto.ontologyName()).containsOnly(Map.entry("cs", "Testovací slovník"));
        }

        @Test
        @DisplayName("an unlabelled scheme leaves ontologyName null")
        void unlabelledScheme() {
            Model m = Ttl.parse("pojem:osoba a skos:Concept ; skos:inScheme ex: .");

            ResolvedConceptDto dto = ConceptResolutionProjection
                    .projectDisplayResolutions(m, SearchSource.NKD, List.of(OSOBA)).get(OSOBA);

            assertThat(dto.ontologyIri()).isEqualTo(Ttl.VOCABULARY);
            assertThat(dto.ontologyName()).isNull();
        }

        @Test
        @DisplayName("relationships get domain/range stubs; other concepts do not")
        void relationshipStubs() {
            Model m = Ttl.parse("""
                    pojem:bydlí-v a slovníky:vztah ; rdfs:domain pojem:osoba ; rdfs:range pojem:adresa .
                    pojem:osoba a slovníky:třída ; rdfs:domain pojem:adresa .
                    """);

            Map<String, ResolvedConceptDto> out = ConceptResolutionProjection
                    .projectDisplayResolutions(m, SearchSource.NKD, List.of(BYDLI, OSOBA));

            assertThat(out.get(BYDLI).resolvedDomain().iri()).isEqualTo(OSOBA);
            assertThat(out.get(BYDLI).resolvedRange().iri()).isEqualTo(ADRESA);
            assertThat(out.get(OSOBA).resolvedDomain()).isNull();
            assertThat(out.get(OSOBA).resolvedRange()).isNull();
        }
    }
}
