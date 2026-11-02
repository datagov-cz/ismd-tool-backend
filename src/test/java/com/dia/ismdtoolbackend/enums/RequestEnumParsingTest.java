package com.dia.ismdtoolbackend.enums;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Request parameters parsed from text: case-insensitive, trimmed, blank means "not given". */
class RequestEnumParsingTest {

    @Nested
    class SearchTypes {

        @ParameterizedTest
        @EnumSource(SearchType.class)
        void parsesItsOwnValueInAnyCaseAndSpacing(SearchType type) {
            assertThat(SearchType.fromString(type.getValue())).isSameAs(type);
            assertThat(SearchType.fromString("  " + type.getValue().toLowerCase() + " ")).isSameAs(type);
            assertThat(type).hasToString(type.getValue());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "   ")
        void blankIsNotGiven(String value) {
            assertThat(SearchType.fromString(value)).isNull();
        }

        @Test
        void unknownValueNamesTheValidOnes() {
            assertThatThrownBy(() -> SearchType.fromString("POJEM"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Unknown search type: POJEM. Valid values: ONTOLOGY, CONCEPT, CLASS, PROPERTY, "
                            + "RELATIONSHIP, DIAGRAM");
        }

        @Test
        void roleFiltersMapToTheirConceptType() {
            assertThat(SearchType.CLASS.toConceptType()).isEqualTo(ConceptType.TRIDA);
            assertThat(SearchType.PROPERTY.toConceptType()).isEqualTo(ConceptType.VLASTNOST);
            assertThat(SearchType.RELATIONSHIP.toConceptType()).isEqualTo(ConceptType.VZTAH);
            assertThat(SearchType.ONTOLOGY.toConceptType()).isNull();
            assertThat(SearchType.CONCEPT.toConceptType()).isNull();
            assertThat(SearchType.DIAGRAM.toConceptType()).isNull();
        }

        @ParameterizedTest
        @EnumSource(SearchType.class)
        void roleFiltersAreExactlyTheTypesWithAConceptType(SearchType type) {
            assertThat(type.isConceptRoleFilter()).isEqualTo(type.toConceptType() != null);
        }

        @ParameterizedTest
        @EnumSource(SearchType.class)
        void anyConceptMeansConceptOrARoleFilter(SearchType type) {
            assertThat(type.isAnyConcept()).isEqualTo(type == SearchType.CONCEPT || type.isConceptRoleFilter());
        }

        @Test
        void jsonUsesTheValue() {
            JsonMapper json = JsonMapper.builder().build();
            assertThat(json.writeValueAsString(SearchType.CLASS)).isEqualTo("\"CLASS\"");
            assertThat(json.readValue("\"class\"", SearchType.class)).isEqualTo(SearchType.CLASS);
        }
    }

    @Nested
    class SearchSources {

        @ParameterizedTest
        @EnumSource(SearchSource.class)
        void parsesItsOwnValueInAnyCaseAndSpacing(SearchSource source) {
            assertThat(SearchSource.fromString(source.getValue())).isSameAs(source);
            assertThat(SearchSource.fromString(" " + source.getValue().toLowerCase() + "  ")).isSameAs(source);
            assertThat(source).hasToString(source.getValue());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "  ")
        void blankIsNotGiven(String value) {
            assertThat(SearchSource.fromString(value)).isNull();
        }

        @Test
        void unknownValueNamesTheValidOnes() {
            assertThatThrownBy(() -> SearchSource.fromString("NKOD"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Unknown search source: NKOD. Valid values: NKD, ISMD, UNPUBLISHED, ALL");
        }
    }

    @Nested
    class RelationTypes {

        @ParameterizedTest
        @EnumSource(RelationType.class)
        void parsesItsOwnValueInAnyCaseAndSpacing(RelationType type) {
            assertThat(RelationType.fromString(type.getValue())).isSameAs(type);
            assertThat(RelationType.fromString("\t" + type.getValue().toLowerCase() + " ")).isSameAs(type);
            assertThat(type).hasToString(type.getValue());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = " ")
        void blankIsNotGiven(String value) {
            assertThat(RelationType.fromString(value)).isNull();
        }

        @Test
        void unknownValueNamesTheValidOnes() {
            assertThatThrownBy(() -> RelationType.fromString("BROADER"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Unknown relation type: BROADER. Valid values: SUBCLASS, SUPERCLASS, EXACT_MATCH, "
                            + "PROPERTY_OF, RELATIONSHIP_OF");
        }
    }
}
