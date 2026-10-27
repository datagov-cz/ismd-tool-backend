package com.dia.ismdtoolbackend.utility.eli;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EsbirkaCzechCitationFormatterTest {

    @Test
    void buildFragmentCitationFromSegments_parPresentDropsAncestors() {
        String citation = EsbirkaCzechCitationFormatter.buildFragmentCitationFromSegments(List.of(
                new ParsedEli.FragmentSegment("cast", "1"),
                new ParsedEli.FragmentSegment("hlava", "1"),
                new ParsedEli.FragmentSegment("par", "2"),
                new ParsedEli.FragmentSegment("pism", "d")));
        assertThat(citation).isEqualTo("§ 2 písm. d)");
    }

    @Test
    void buildFragmentCitationFromSegments_noParKeepsAllSegments() {
        String citation = EsbirkaCzechCitationFormatter.buildFragmentCitationFromSegments(List.of(
                new ParsedEli.FragmentSegment("cast", "1"),
                new ParsedEli.FragmentSegment("hlava", "1")));
        assertThat(citation).isEqualTo("Část 1 Hlava 1");
    }

    @Test
    void buildFragmentCitationFromSegments_emptyReturnsEmpty() {
        assertThat(EsbirkaCzechCitationFormatter.buildFragmentCitationFromSegments(List.of())).isEqualTo("");
    }

    @Test
    void buildFragmentCitationFromSegments_nullReturnsEmpty() {
        assertThat(EsbirkaCzechCitationFormatter.buildFragmentCitationFromSegments(null)).isEqualTo("");
    }

    @Test
    void buildFragmentCitationFromSegments_unknownKindFallsThrough() {
        String citation = EsbirkaCzechCitationFormatter.buildFragmentCitationFromSegments(List.of(
                new ParsedEli.FragmentSegment("mystery", "42")));
        assertThat(citation).isEqualTo("mystery 42");
    }

    @Test
    void buildDisplayLabel_invalidParsedReturnsNull() {
        ParsedEli invalid = new ParsedEli(null, null, null, null, null, null, null, null, null, null, List.of(), null, null);
        assertThat(EsbirkaCzechCitationFormatter.buildDisplayLabel(invalid, null)).isNull();
    }

    @Test
    void buildDisplayLabel_lawOnly() {
        ParsedEli p = new ParsedEli(
                "url", "domain", "/eli/cz/sb/2000/361",
                "lawIri", null, null,
                "361", 2000, "sb", null, List.of(), ParsedEli.Level.LAW, null);
        assertThat(EsbirkaCzechCitationFormatter.buildDisplayLabel(p, null)).isEqualTo("Zákon č. 361/2000 Sb.");
    }

    @Test
    void buildDisplayLabel_versionAppendsZneniOd() {
        ParsedEli p = new ParsedEli(
                "url", "domain", "/eli/cz/sb/2000/361/2024-04-01",
                "lawIri", "verIri", null,
                "361", 2000, "sb", LocalDate.of(2024, 4, 1), List.of(), ParsedEli.Level.VERSION, null);
        assertThat(EsbirkaCzechCitationFormatter.buildDisplayLabel(p, null))
                .isEqualTo("Zákon č. 361/2000 Sb. (znění od 1. 4. 2024)");
    }

    @Test
    void buildDisplayLabel_versionWithNullDateOmitsZneni() {
        ParsedEli p = new ParsedEli(
                "url", "domain", "/eli/cz/sb/2000/361/not-a-date",
                "lawIri", "verIri", null,
                "361", 2000, "sb", null, List.of(), ParsedEli.Level.VERSION, null);
        assertThat(EsbirkaCzechCitationFormatter.buildDisplayLabel(p, null)).isEqualTo("Zákon č. 361/2000 Sb.");
    }

    @Test
    void buildDisplayLabel_fragmentUsesSparqlCitationWhenPresent() {
        ParsedEli p = new ParsedEli(
                "url", "domain", "/eli/cz/sb/2000/361/2024-04-01/dokument/norma/par_2/pism_d",
                "lawIri", "verIri", "fragIri",
                "361", 2000, "sb", LocalDate.of(2024, 4, 1),
                List.of(new ParsedEli.FragmentSegment("par", "2"),
                        new ParsedEli.FragmentSegment("pism", "d")),
                ParsedEli.Level.FRAGMENT, null);
        String label = EsbirkaCzechCitationFormatter.buildDisplayLabel(p, "§ 2 písm. d)");
        assertThat(label).isEqualTo("Zákon č. 361/2000 Sb., § 2 písm. d) (znění od 1. 4. 2024)");
    }

    @Test
    void buildDisplayLabel_fragmentFallsBackToSegmentFormatterWhenSparqlNull() {
        ParsedEli p = new ParsedEli(
                "url", "domain", "path",
                "lawIri", "verIri", "fragIri",
                "361", 2000, "sb", LocalDate.of(2024, 4, 1),
                List.of(new ParsedEli.FragmentSegment("par", "2"),
                        new ParsedEli.FragmentSegment("odst", "1")),
                ParsedEli.Level.FRAGMENT, null);
        String label = EsbirkaCzechCitationFormatter.buildDisplayLabel(p, null);
        assertThat(label).isEqualTo("Zákon č. 361/2000 Sb., § 2 odst. 1 (znění od 1. 4. 2024)");
    }

    @Test
    void buildDisplayLabel_fragmentBlankSparqlFallsBackToSegments() {
        ParsedEli p = new ParsedEli(
                "url", "domain", "path",
                "lawIri", "verIri", "fragIri",
                "361", 2000, "sb", null,
                List.of(new ParsedEli.FragmentSegment("par", "2")),
                ParsedEli.Level.FRAGMENT, null);
        assertThat(EsbirkaCzechCitationFormatter.buildDisplayLabel(p, "   ")).isEqualTo("Zákon č. 361/2000 Sb., § 2");
    }

    @Test
    void buildDisplayLabel_containerRootUsesContainerLabel() {
        ParsedEli p = new ParsedEli(
                "url", "domain", "path",
                "lawIri", "verIri", "fragIri",
                "72", 2026, "sb", LocalDate.of(2026, 7, 1),
                List.of(), ParsedEli.Level.FRAGMENT, "poznamkypodcarou");
        assertThat(EsbirkaCzechCitationFormatter.buildDisplayLabel(p, null))
                .isEqualTo("Zákon č. 72/2026 Sb., Poznámky pod čarou (znění od 1. 7. 2026)");
    }

    @Test
    void buildDisplayLabel_documentRootLabelsWholeText() {
        ParsedEli p = new ParsedEli(
                "url", "domain", "path",
                "lawIri", "verIri", "fragIri",
                "23", 2024, "sb", null,
                List.of(), ParsedEli.Level.FRAGMENT, null);
        assertThat(EsbirkaCzechCitationFormatter.buildDisplayLabel(p, null))
                .isEqualTo("Zákon č. 23/2024 Sb., úplné znění");
    }

    @Test
    void buildDisplayLabel_unknownContainerRootOmitsFragmentPart() {
        ParsedEli p = new ParsedEli(
                "url", "domain", "path",
                "lawIri", "verIri", "fragIri",
                "72", 2026, "sb", null,
                List.of(), ParsedEli.Level.FRAGMENT, "neznamy");
        assertThat(EsbirkaCzechCitationFormatter.buildDisplayLabel(p, null)).isEqualTo("Zákon č. 72/2026 Sb.");
    }

    @Test
    void formatCzechDate_nullReturnsNull() {
        assertThat(EsbirkaCzechCitationFormatter.formatCzechDate(null)).isNull();
    }

    @Test
    void formatCzechDate_singleDigitNoLeadingZeroes() {
        assertThat(EsbirkaCzechCitationFormatter.formatCzechDate(LocalDate.of(2024, 4, 1))).isEqualTo("1. 4. 2024");
    }

    @Test
    void formatCzechDate_doubleDigit() {
        assertThat(EsbirkaCzechCitationFormatter.formatCzechDate(LocalDate.of(2024, 12, 31))).isEqualTo("31. 12. 2024");
    }
}
