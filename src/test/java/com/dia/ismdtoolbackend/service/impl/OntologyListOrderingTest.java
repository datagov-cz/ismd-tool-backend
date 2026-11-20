package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.OntologySummaryDto;
import com.dia.ismdtoolbackend.models.OntologyListSort;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OntologyListOrderingTest {

    private static OntologySummaryDto row(long id, Map<String, String> name, String slug) {
        OntologySummaryDto dto = new OntologySummaryDto();
        dto.setId(id);
        dto.setName(name);
        dto.setSlug(slug);
        return dto;
    }

    private static List<Long> ids(List<OntologySummaryDto> rows) {
        return rows.stream().map(OntologySummaryDto::getId).toList();
    }

    @Test
    void qMatchesNameDescriptionAndSlugIgnoringCaseAndDiacritics() {
        OntologySummaryDto byName = row(1, Map.of("cs", "Školní matrika"), "a");
        OntologySummaryDto byDescription = row(2, Map.of("cs", "Jiný"), "b");
        byDescription.setPopis(Map.of("en", "About SCHOOLS and ŠKOLY"));
        OntologySummaryDto bySlug = row(3, null, "skolstvi");
        OntologySummaryDto none = row(4, Map.of("cs", "Doprava"), "doprava");

        List<OntologySummaryDto> out = OntologyListOrdering.filterAndSort(
                List.of(byName, byDescription, bySlug, none), "ŠKOL", OntologyListSort.NAME);

        assertThat(ids(out)).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void nameOrderIsCzechAndFallsBackToAnyNameThenSlug() {
        List<OntologySummaryDto> rows = List.of(
                row(1, Map.of("cs", "Čeština"), "x"),
                row(2, Map.of("cs", "Chemie"), "x"),
                row(3, Map.of("cs", "Cyklistika"), "x"),
                row(4, Map.of("en", "Doctors"), "x"),
                row(5, null, "aaa"));

        // Czech collation: C < Č < D < … < H < Ch.
        assertThat(ids(OntologyListOrdering.filterAndSort(rows, null, OntologyListSort.NAME)))
                .containsExactly(5L, 3L, 1L, 4L, 2L);
    }

    @Test
    void equalNamesKeepIdOrder() {
        List<OntologySummaryDto> rows = List.of(row(9, Map.of("cs", "Stejný"), "x"), row(2, Map.of("cs", "Stejný"), "y"));

        assertThat(ids(OntologyListOrdering.filterAndSort(rows, null, OntologyListSort.NAME))).containsExactly(2L, 9L);
    }

    @Test
    void updatedOrderIsNewestFirstWithUndatedLast() {
        OntologySummaryDto old = row(1, null, "a");
        old.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        OntologySummaryDto recent = row(2, null, "b");
        recent.setUpdatedAt(LocalDateTime.of(2026, 11, 1, 0, 0));
        OntologySummaryDto undated = row(3, null, "c");
        OntologySummaryDto sameInstant = row(4, null, "d");
        sameInstant.setUpdatedAt(LocalDateTime.of(2026, 11, 1, 0, 0));

        assertThat(ids(OntologyListOrdering.filterAndSort(List.of(old, recent, undated, sameInstant), null,
                OntologyListSort.UPDATED))).containsExactly(4L, 2L, 1L, 3L);
    }

    @Test
    void foldStripsDiacriticsAndCase() {
        assertThat(OntologyListOrdering.fold("Příliš ŽLUŤOUČKÝ kůň")).isEqualTo("prilis zlutoucky kun");
    }
}
