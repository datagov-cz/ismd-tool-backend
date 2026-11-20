package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.OntologySummaryDto;
import com.dia.ismdtoolbackend.models.OntologyListSort;

import java.text.Collator;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Text filter and order of the ontology list. Both run on enriched rows: names and descriptions live in RDF,
 * so PostgreSQL can neither match nor sort on them.
 */
final class OntologyListOrdering {

    private static final Locale CZECH = Locale.forLanguageTag("cs");

    private OntologyListOrdering() {
    }

    static List<OntologySummaryDto> filterAndSort(List<OntologySummaryDto> rows, String q, OntologyListSort sort) {
        Stream<OntologySummaryDto> stream = rows.stream();
        if (q != null) {
            String needle = fold(q);
            stream = stream.filter(row -> matches(row, needle));
        }
        return stream.sorted(comparator(sort)).toList();
    }

    private static boolean matches(OntologySummaryDto row, String needle) {
        return Stream.of(values(row.getName()), values(row.getPopis()), Stream.of(row.getSlug()))
                .flatMap(s -> s)
                .filter(Objects::nonNull)
                .anyMatch(text -> fold(text).contains(needle));
    }

    private static Stream<String> values(Map<String, String> byLanguage) {
        return byLanguage == null ? Stream.empty() : byLanguage.values().stream();
    }

    /** Lower case without diacritics, so "slovnik" finds "Slovník". */
    static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(CZECH);
    }

    private static Comparator<OntologySummaryDto> comparator(OntologyListSort sort) {
        Comparator<OntologySummaryDto> byId = Comparator.comparing(OntologySummaryDto::getId,
                Comparator.nullsLast(Comparator.naturalOrder()));
        if (sort == OntologyListSort.UPDATED) {
            return Comparator.comparing(OntologySummaryDto::getUpdatedAt,
                            Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder()))
                    .thenComparing(byId.reversed());
        }
        Collator collator = Collator.getInstance(CZECH);
        return Comparator.comparing(OntologyListOrdering::displayName, collator).thenComparing(byId);
    }

    private static String displayName(OntologySummaryDto row) {
        Map<String, String> name = row.getName();
        if (name != null) {
            String cs = name.get("cs");
            if (cs != null && !cs.isBlank()) {
                return cs;
            }
            for (String any : name.values()) {
                if (any != null && !any.isBlank()) {
                    return any;
                }
            }
        }
        return row.getSlug() == null ? "" : row.getSlug();
    }
}
