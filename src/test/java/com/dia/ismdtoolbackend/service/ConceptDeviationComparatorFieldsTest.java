package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.controller.dto.NonLegalSourceDto;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel.ConceptDetailModelBuilder;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.DeviationStatus;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.PropertyDeviation;
import com.dia.ismdtoolbackend.service.impl.ConceptDeviationComparator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Every compared characteristic is reported under its own field, with both values, and nowhere else. */
class ConceptDeviationComparatorFieldsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConceptDeviationComparator comparator = new ConceptDeviationComparator();

    record Field<T>(String jsonKey,
                    BiFunction<ConceptDetailModelBuilder, T, ConceptDetailModelBuilder> setter,
                    Function<PublishedConceptDeviationModel, PropertyDeviation<T>> deviation,
                    T one, T other) {

        ConceptDetailModel conceptWith(T value) {
            return setter.apply(ConceptDetailModel.builder(), value).build();
        }

        @Override
        public String toString() {
            return jsonKey;
        }
    }

    static Stream<Field<?>> fields() {
        List<NonLegalSourceDto> sourceA = List.of(NonLegalSourceDto.builder().url("https://example.org/a").build());
        List<NonLegalSourceDto> sourceB = List.of(NonLegalSourceDto.builder().url("https://example.org/b").build());
        List<String> eliA = List.of("https://e-sbirka.gov.cz/eli/cz/sb/1997/49");
        List<String> eliB = List.of("https://e-sbirka.gov.cz/eli/cz/sb/2000/361");
        return Stream.of(
                new Field<>("typ", ConceptDetailModelBuilder::types,
                        PublishedConceptDeviationModel::getTypes, List.of("Třída"), List.of("Vztah")),
                new Field<Map<String, Object>>("alternativní-název", ConceptDetailModelBuilder::alternativeName,
                        PublishedConceptDeviationModel::getAlternativeName,
                        Map.of("cs", List.of("a")), Map.of("cs", List.of("b"))),
                new Field<>("definice", ConceptDetailModelBuilder::definition,
                        PublishedConceptDeviationModel::getDefinition, Map.of("cs", "a"), Map.of("cs", "b")),
                new Field<>("popis", ConceptDetailModelBuilder::description,
                        PublishedConceptDeviationModel::getDescription, Map.of("cs", "a"), Map.of("cs", "b")),
                new Field<>("identifikátor", ConceptDetailModelBuilder::identifier,
                        PublishedConceptDeviationModel::getIdentifier, "a", "b"),
                new Field<>("nadřazená-třída", ConceptDetailModelBuilder::broaderClasses,
                        PublishedConceptDeviationModel::getBroaderClasses, List.of("a"), List.of("b")),
                new Field<>("nadřazený-vztah", ConceptDetailModelBuilder::broaderRelations,
                        PublishedConceptDeviationModel::getBroaderRelations, List.of("a"), List.of("b")),
                new Field<>("nadřazená-vlastnost", ConceptDetailModelBuilder::broaderProperties,
                        PublishedConceptDeviationModel::getBroaderProperties, List.of("a"), List.of("b")),
                new Field<>("definiční-obor", ConceptDetailModelBuilder::domain,
                        PublishedConceptDeviationModel::getDomain, "a", "b"),
                new Field<>("obor-hodnot", ConceptDetailModelBuilder::range,
                        PublishedConceptDeviationModel::getRange, "a", "b"),
                new Field<>("ekvivalentní-pojem", ConceptDetailModelBuilder::exactMatches,
                        PublishedConceptDeviationModel::getExactMatches, List.of("a"), List.of("b")),
                new Field<>("definující-ustanovení-právního-předpisu", ConceptDetailModelBuilder::definingLegalSources,
                        PublishedConceptDeviationModel::getDefiningLegalSources, eliA, eliB),
                new Field<>("související-ustanovení-právního-předpisu", ConceptDetailModelBuilder::relatedLegalSources,
                        PublishedConceptDeviationModel::getRelatedLegalSources, eliA, eliB),
                new Field<>("definující-nelegislativní-zdroj", ConceptDetailModelBuilder::definingNonLegalSources,
                        PublishedConceptDeviationModel::getDefiningNonLegalSources, sourceA, sourceB),
                new Field<>("související-nelegislativní-zdroj", ConceptDetailModelBuilder::relatedNonLegalSources,
                        PublishedConceptDeviationModel::getRelatedNonLegalSources, sourceA, sourceB),
                new Field<>("způsob-sdílení-údajů", ConceptDetailModelBuilder::sharingMethods,
                        PublishedConceptDeviationModel::getSharingMethods, List.of("a"), List.of("b")),
                new Field<>("způsob-získání-údajů", ConceptDetailModelBuilder::acquisitionMethod,
                        PublishedConceptDeviationModel::getAcquisitionMethod, "a", "b"),
                new Field<>("typ-obsahu-údajů", ConceptDetailModelBuilder::contentType,
                        PublishedConceptDeviationModel::getContentType, "a", "b"),
                new Field<>("je-ppdf", ConceptDetailModelBuilder::isPpdf,
                        PublishedConceptDeviationModel::getIsPpdf, true, false),
                new Field<>("ais", ConceptDetailModelBuilder::ais,
                        PublishedConceptDeviationModel::getAis, "a", "b"),
                new Field<>("agenda", ConceptDetailModelBuilder::agenda,
                        PublishedConceptDeviationModel::getAgenda, "a", "b"),
                new Field<>("ustanovení-dokládající-neveřejnost-údaje", ConceptDetailModelBuilder::privacyProvisions,
                        PublishedConceptDeviationModel::getPrivacyProvisions, eliA, eliB));
    }

    @ParameterizedTest
    @MethodSource("fields")
    <T> void differingValueIsReportedUnderItsOwnFieldOnly(Field<T> field) {
        PublishedConceptDeviationModel result = comparator.compareConceptDetails(
                field.conceptWith(field.one()), field.conceptWith(field.other()));

        assertThat(result.getStatus()).isEqualTo(DeviationStatus.HAS_DEVIATIONS);
        PropertyDeviation<T> deviation = field.deviation().apply(result);
        assertThat(deviation).isNotNull();
        assertThat(deviation.getLocalValue()).isEqualTo(field.one());
        assertThat(deviation.getPublishedValue()).isEqualTo(field.other());
        assertThat(deviation.isDifferent()).isTrue();
        assertThat(reportedKeys(result)).containsExactlyInAnyOrder("status", field.jsonKey());
    }

    @ParameterizedTest
    @MethodSource("fields")
    <T> void equalValueIsNotReported(Field<T> field) {
        PublishedConceptDeviationModel result = comparator.compareConceptDetails(
                field.conceptWith(field.one()), field.conceptWith(field.one()));

        assertThat(result.getStatus()).isEqualTo(DeviationStatus.NO_DEVIATION);
        assertThat(reportedKeys(result)).containsExactly("status");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> reportedKeys(PublishedConceptDeviationModel result) {
        return MAPPER.convertValue(result, Map.class).keySet();
    }
}
