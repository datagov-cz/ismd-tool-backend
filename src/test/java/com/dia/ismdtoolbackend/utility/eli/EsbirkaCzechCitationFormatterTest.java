package com.dia.ismdtoolbackend.utility.eli;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EsbirkaCzechCitationFormatterTest {

    @Test
    void buildFragmentCitationFromSegments_parPresentDropsAncestors() {
        String citation = EsbirkaCzechCitationFormatter.buildFragmentCitationFromSegments(List.of(
                new ParsedEli.FragmentSegment("cast", "1"),
                new ParsedEli.FragmentSegment("hlava", "1"),
                new ParsedEli.FragmentSegment("par", "2"),
                new ParsedEli.FragmentSegment("pism", "d")));
        assertEquals("§ 2 písm. d)", citation);
    }

    @Test
    void buildFragmentCitationFromSegments_noParKeepsAllSegments() {
        String citation = EsbirkaCzechCitationFormatter.buildFragmentCitationFromSegments(List.of(
                new ParsedEli.FragmentSegment("cast", "1"),
                new ParsedEli.FragmentSegment("hlava", "1")));
        assertEquals("Část 1 Hlava 1", citation);
    }

    @Test
    void buildFragmentCitationFromSegments_emptyReturnsEmpty() {
        assertEquals("", EsbirkaCzechCitationFormatter.buildFragmentCitationFromSegments(List.of()));
    }

    @Test
    void buildFragmentCitationFromSegments_nullReturnsEmpty() {
        assertEquals("", EsbirkaCzechCitationFormatter.buildFragmentCitationFromSegments(null));
    }

    @Test
    void buildFragmentCitationFromSegments_unknownKindFallsThrough() {
        String citation = EsbirkaCzechCitationFormatter.buildFragmentCitationFromSegments(List.of(
                new ParsedEli.FragmentSegment("mystery", "42")));
        assertEquals("mystery 42", citation);
    }

    @Test
    void buildDisplayLabel_invalidParsedReturnsNull() {
        ParsedEli invalid = new ParsedEli(null, null, null, null, null, null, null, null, null, null, List.of(), null);
        assertNull(EsbirkaCzechCitationFormatter.buildDisplayLabel(invalid, null));
    }

    @Test
    void buildDisplayLabel_lawOnly() {
        ParsedEli p = new ParsedEli(
                "url", "domain", "/eli/cz/sb/2000/361",
                "lawIri", null, null,
                "361", 2000, "sb", null, List.of(), ParsedEli.Level.LAW);
        assertEquals("Zákon č. 361/2000 Sb.", EsbirkaCzechCitationFormatter.buildDisplayLabel(p, null));
    }

    @Test
    void buildDisplayLabel_versionAppendsZneniOd() {
        ParsedEli p = new ParsedEli(
                "url", "domain", "/eli/cz/sb/2000/361/2024-04-01",
                "lawIri", "verIri", null,
                "361", 2000, "sb", LocalDate.of(2024, 4, 1), List.of(), ParsedEli.Level.VERSION);
        assertEquals("Zákon č. 361/2000 Sb. (znění od 1. 4. 2024)",
                EsbirkaCzechCitationFormatter.buildDisplayLabel(p, null));
    }

    @Test
    void buildDisplayLabel_versionWithNullDateOmitsZneni() {
        ParsedEli p = new ParsedEli(
                "url", "domain", "/eli/cz/sb/2000/361/not-a-date",
                "lawIri", "verIri", null,
                "361", 2000, "sb", null, List.of(), ParsedEli.Level.VERSION);
        assertEquals("Zákon č. 361/2000 Sb.", EsbirkaCzechCitationFormatter.buildDisplayLabel(p, null));
    }

    @Test
    void buildDisplayLabel_fragmentUsesSparqlCitationWhenPresent() {
        ParsedEli p = new ParsedEli(
                "url", "domain", "/eli/cz/sb/2000/361/2024-04-01/dokument/norma/par_2/pism_d",
                "lawIri", "verIri", "fragIri",
                "361", 2000, "sb", LocalDate.of(2024, 4, 1),
                List.of(new ParsedEli.FragmentSegment("par", "2"),
                        new ParsedEli.FragmentSegment("pism", "d")),
                ParsedEli.Level.FRAGMENT);
        String label = EsbirkaCzechCitationFormatter.buildDisplayLabel(p, "§ 2 písm. d)");
        assertEquals("Zákon č. 361/2000 Sb., § 2 písm. d) (znění od 1. 4. 2024)", label);
    }

    @Test
    void buildDisplayLabel_fragmentFallsBackToSegmentFormatterWhenSparqlNull() {
        ParsedEli p = new ParsedEli(
                "url", "domain", "path",
                "lawIri", "verIri", "fragIri",
                "361", 2000, "sb", LocalDate.of(2024, 4, 1),
                List.of(new ParsedEli.FragmentSegment("par", "2"),
                        new ParsedEli.FragmentSegment("odst", "1")),
                ParsedEli.Level.FRAGMENT);
        String label = EsbirkaCzechCitationFormatter.buildDisplayLabel(p, null);
        assertEquals("Zákon č. 361/2000 Sb., § 2 odst. 1 (znění od 1. 4. 2024)", label);
    }

    @Test
    void buildDisplayLabel_fragmentBlankSparqlFallsBackToSegments() {
        ParsedEli p = new ParsedEli(
                "url", "domain", "path",
                "lawIri", "verIri", "fragIri",
                "361", 2000, "sb", null,
                List.of(new ParsedEli.FragmentSegment("par", "2")),
                ParsedEli.Level.FRAGMENT);
        assertEquals("Zákon č. 361/2000 Sb., § 2",
                EsbirkaCzechCitationFormatter.buildDisplayLabel(p, "   "));
    }

    @Test
    void formatCzechDate_nullReturnsNull() {
        assertNull(EsbirkaCzechCitationFormatter.formatCzechDate(null));
    }

    @Test
    void formatCzechDate_singleDigitNoLeadingZeroes() {
        assertEquals("1. 4. 2024", EsbirkaCzechCitationFormatter.formatCzechDate(LocalDate.of(2024, 4, 1)));
    }

    @Test
    void formatCzechDate_doubleDigit() {
        assertEquals("31. 12. 2024", EsbirkaCzechCitationFormatter.formatCzechDate(LocalDate.of(2024, 12, 31)));
    }
}
