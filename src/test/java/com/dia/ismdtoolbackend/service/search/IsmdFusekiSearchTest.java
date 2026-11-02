package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.exception.ConflictingConceptTypeException;
import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.MatchedBy;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.enums.SearchType;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE;
import static com.dia.constants.VocabularyConstants.VLASTNOST;
import static com.dia.constants.VocabularyConstants.VZTAH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Row classification and label enrichment of {@link IsmdFusekiSearch}. */
@ExtendWith(MockitoExtension.class)
class IsmdFusekiSearchTest {

    private static final String GRAPH = Ttl.VOCABULARY;
    private static final String OSOBA = Ttl.POJEM + "osoba";
    private static final String ADRESA = Ttl.POJEM + "adresa";
    private static final String SKOS_CONCEPT = "http://www.w3.org/2004/02/skos/core#Concept";
    private static final String OWL_ONTOLOGY = "http://www.w3.org/2002/07/owl#Ontology";
    private static final String OWL_OBJECT_PROPERTY = "http://www.w3.org/2002/07/owl#ObjectProperty";

    @Mock
    private JenaTDB2Repository repository;

    @InjectMocks
    private IsmdFusekiSearch search;

    private static Map<String, String> row(String iri, String types) {
        Map<String, String> row = new HashMap<>();
        row.put("resourceIri", iri);
        row.put("graphName", GRAPH);
        row.put("prefLabel", "Osoba");
        row.put("prefLabelLang", "cs");
        if (types != null) row.put("types", types);
        return row;
    }

    @Nested
    @DisplayName("searchFuseki")
    class SearchFuseki {

        @Test
        @DisplayName("no visible graphs skips the repository")
        void noGraphs() {
            assertThat(search.searchFuseki("osoba", List.of(), 10, null)).isEmpty();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("concept rows carry their graph and role; ontology rows carry neither")
        void classifiesRows() {
            when(repository.searchByText("osoba", List.of(GRAPH), 10, null)).thenReturn(List.of(
                    row(OSOBA, SKOS_CONCEPT + "|" + OFN_NAMESPACE + VLASTNOST),
                    row(GRAPH, "http://www.w3.org/2004/02/skos/core#ConceptScheme|" + OWL_ONTOLOGY),
                    row(ADRESA, null)));

            List<SearchResultDto> out = search.searchFuseki("osoba", List.of(GRAPH), 10, null);

            assertThat(out).extracting(SearchResultDto::getType)
                    .containsExactly(SearchType.CONCEPT, SearchType.ONTOLOGY, SearchType.CONCEPT);
            assertThat(out.get(0).getOntologyIri()).isEqualTo(GRAPH);
            assertThat(out.get(0).getConceptType()).isEqualTo(ConceptType.VLASTNOST);
            assertThat(out.get(0).getLabel()).isEqualTo("Osoba");
            assertThat(out.get(0).getSource()).isEqualTo(SearchSource.ISMD);
            assertThat(out.get(0).getMatchedBy()).isEqualTo(MatchedBy.SPARQL);
            assertThat(out.get(1).getOntologyIri()).isNull();
            assertThat(out.get(1).getConceptType()).isNull();
            assertThat(out.get(2).getConceptType()).isNull();
        }

        @Test
        @DisplayName("a dual-typed concept fails the search and names the concept")
        void dualTypedRowThrowsNamingTheConcept() {
            when(repository.searchByText("osoba", List.of(GRAPH), 10, null)).thenReturn(List.of(
                    row(ADRESA, OFN_NAMESPACE + VZTAH),
                    row(OSOBA, OFN_NAMESPACE + VLASTNOST + "|" + OWL_OBJECT_PROPERTY)));

            assertThatThrownBy(() -> search.searchFuseki("osoba", List.of(GRAPH), 10, null))
                    .isInstanceOf(ConflictingConceptTypeException.class)
                    .hasMessageContaining("VLASTNOST + VZTAH")
                    .hasMessageContaining(OSOBA);
        }
    }

    @Nested
    @DisplayName("type classification")
    class Classification {

        @Test
        @DisplayName("missing or empty types classify as CONCEPT with no role")
        void missingTypes() {
            assertThat(IsmdFusekiSearch.classifyByRdfTypes(null)).isEqualTo(SearchType.CONCEPT);
            assertThat(IsmdFusekiSearch.classifyByRdfTypes("")).isEqualTo(SearchType.CONCEPT);
            assertThat(IsmdFusekiSearch.conceptTypeByRdfTypes(null)).isNull();
            assertThat(IsmdFusekiSearch.conceptTypeByRdfTypes("")).isNull();
        }

        @Test
        @DisplayName("owl:Ontology alone is decisive for ONTOLOGY")
        void owlOntology() {
            assertThat(IsmdFusekiSearch.classifyByRdfTypes(SKOS_CONCEPT + "|" + OWL_ONTOLOGY))
                    .isEqualTo(SearchType.ONTOLOGY);
        }

        @Test
        @DisplayName("a plain skos:Concept has no role; OWL types map to roles")
        void roles() {
            assertThat(IsmdFusekiSearch.conceptTypeByRdfTypes(SKOS_CONCEPT)).isNull();
            assertThat(IsmdFusekiSearch.conceptTypeByRdfTypes(SKOS_CONCEPT + "|" + OWL_OBJECT_PROPERTY))
                    .isEqualTo(ConceptType.VZTAH);
        }

        @Test
        @DisplayName("conflicting role markers throw")
        void conflictingRolesThrow() {
            assertThatThrownBy(() -> IsmdFusekiSearch.conceptTypeByRdfTypes(
                    OFN_NAMESPACE + VLASTNOST + "|" + OFN_NAMESPACE + VZTAH))
                    .isInstanceOf(ConflictingConceptTypeException.class);
        }
    }

    @Nested
    @DisplayName("enrichWithLabels")
    class Enrich {

        private SearchResultDto dto(String iri) {
            return SearchResultDto.builder().iri(iri).label("PG label").labelLang("en")
                    .altName("PG alt").description("PG desc").definition("PG def").build();
        }

        @Test
        @DisplayName("no IRIs skips the repository")
        void noIris() {
            search.enrichWithLabels(new ArrayList<>(List.of(SearchResultDto.builder().build())), "cs");
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("requested language wins, otherwise the first value; absent fields keep PG values")
        void languagePreferenceAndFallback() {
            when(repository.fetchConceptLabels(anyList())).thenReturn(Ttl.parse("""
                    pojem:osoba skos:prefLabel "Person"@en, "Personne"@fr, "Osoba"@cs ;
                        skos:altLabel "Člověk"@cs ;
                        dct:description pojem:adresa, "Description"@en .
                    pojem:adresa skos:altLabel pojem:osoba .
                    """));
            SearchResultDto osoba = dto(OSOBA);
            SearchResultDto adresa = dto(ADRESA);
            SearchResultDto noIri = SearchResultDto.builder().label("keep").build();

            search.enrichWithLabels(List.of(osoba, adresa, noIri, dto(OSOBA)), "cs");

            verify(repository).fetchConceptLabels(List.of(OSOBA, ADRESA));
            assertThat(osoba.getLabel()).isEqualTo("Osoba");
            assertThat(osoba.getLabelLang()).isEqualTo("cs");
            assertThat(osoba.getAltName()).isEqualTo("Člověk");
            assertThat(osoba.getDescription()).isEqualTo("Description");
            assertThat(osoba.getDefinition()).isEqualTo("PG def");
            assertThat(adresa.getLabel()).isEqualTo("PG label");
            assertThat(adresa.getLabelLang()).isEqualTo("en");
            assertThat(adresa.getAltName()).isEqualTo("PG alt");
            assertThat(noIri.getLabel()).isEqualTo("keep");
        }

        @Test
        @DisplayName("a null language takes the first value found")
        void nullLanguage() {
            when(repository.fetchConceptLabels(anyList())).thenReturn(Ttl.parse("""
                    pojem:osoba skos:definition "Definice"@cs .
                    """));
            SearchResultDto osoba = dto(OSOBA);

            search.enrichWithLabels(List.of(osoba), null);

            assertThat(osoba.getDefinition()).isEqualTo("Definice");
            assertThat(osoba.getLabel()).isEqualTo("PG label");
        }
    }
}
