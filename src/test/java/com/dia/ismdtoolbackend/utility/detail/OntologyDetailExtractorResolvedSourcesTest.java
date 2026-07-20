package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto.EnrichmentStatus;
import com.dia.ismdtoolbackend.utility.eli.ParsedEli;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OntologyDetailExtractorResolvedSourcesTest {

    private static final String LAW = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2000/361";
    private static final String VERSION = LAW + "/2024-04-01";
    private static final String FRAGMENT = VERSION + "/dokument/norma/par_2/pism_d";
    private static final String LEGACY_FRAGMENT =
            "https://opendata.eselpoint.cz/esel-esb/eli/cz/sb/2000/361/2024-04-01/dokument/norma/par_5";

    @Test
    void buildResolvedSources_nullInput_returnsEmptyList() {
        assertTrue(OntologyDetailExtractor.buildResolvedSources(null).isEmpty(),
                "stable contract: empty list, never null, so the field serializes as []");
    }

    @Test
    void buildResolvedSources_emptyInput_returnsEmptyList() {
        assertTrue(OntologyDetailExtractor.buildResolvedSources(List.of()).isEmpty(),
                "stable contract: empty list, never null, so the field serializes as []");
    }

    @Test
    void buildResolvedSources_lawUrl_skippedNonFragment() {
        List<ResolvedLegalSourceDto> out = OntologyDetailExtractor.buildResolvedSources(List.of(LAW));
        assertEquals(1, out.size());
        assertEquals(EnrichmentStatus.SKIPPED_NON_FRAGMENT, out.get(0).getEnrichmentStatus());
        assertEquals(ParsedEli.Level.LAW, out.get(0).getLevel());
        assertEquals("Zákon č. 361/2000 Sb.", out.get(0).getDisplayLabel(),
                "extractor populates parse-only displayLabel via EsbirkaCzechCitationFormatter");
    }

    @Test
    void buildResolvedSources_fragmentUrl_pendingHasFallbackDisplayLabel() {
        List<ResolvedLegalSourceDto> out = OntologyDetailExtractor.buildResolvedSources(List.of(FRAGMENT));
        assertEquals(EnrichmentStatus.PENDING, out.get(0).getEnrichmentStatus());
        assertEquals("Zákon č. 361/2000 Sb., § 2 písm. d) (znění od 1. 4. 2024)",
                out.get(0).getDisplayLabel(),
                "fragment fallback uses segment-derived citation");
    }

    @Test
    void buildResolvedSources_fragmentUrl_pending() {
        List<ResolvedLegalSourceDto> out = OntologyDetailExtractor.buildResolvedSources(List.of(FRAGMENT));
        assertEquals(1, out.size());
        assertEquals(EnrichmentStatus.PENDING, out.get(0).getEnrichmentStatus());
        assertEquals(ParsedEli.Level.FRAGMENT, out.get(0).getLevel());
        assertNull(out.get(0).getFragmentCitation(), "extractor never calls SPARQL");
    }

    @Test
    void buildResolvedSources_invalidUrl_invalidIri() {
        List<ResolvedLegalSourceDto> out = OntologyDetailExtractor.buildResolvedSources(List.of("garbage"));
        assertEquals(1, out.size());
        assertEquals(EnrichmentStatus.INVALID_IRI, out.get(0).getEnrichmentStatus());
        assertEquals("garbage", out.get(0).getOriginalUrl());
    }

    @Test
    void buildResolvedSources_fragmentSegments_neverNull() {
        // INVALID_IRI builds the DTO without touching fragmentSegments — getter must still
        // return an empty list, never null, so the field always serializes as [].
        ResolvedLegalSourceDto invalid =
                OntologyDetailExtractor.buildResolvedSources(List.of("garbage")).get(0);
        assertTrue(invalid.getFragmentSegments().isEmpty(),
                "fragmentSegments must be empty, never null, even for INVALID_IRI");

        // non-fragment parse leaves no segments either
        ResolvedLegalSourceDto law =
                OntologyDetailExtractor.buildResolvedSources(List.of(LAW)).get(0);
        assertTrue(law.getFragmentSegments().isEmpty(),
                "fragmentSegments must be empty, never null, for non-fragment URLs");
    }

    @Test
    void buildResolvedSources_mixedList_preservesOrderAndStatuses() {
        List<ResolvedLegalSourceDto> out = OntologyDetailExtractor.buildResolvedSources(
                List.of(LAW, FRAGMENT, "junk", VERSION));

        assertEquals(4, out.size());
        assertEquals(EnrichmentStatus.SKIPPED_NON_FRAGMENT, out.get(0).getEnrichmentStatus());
        assertEquals(EnrichmentStatus.PENDING, out.get(1).getEnrichmentStatus());
        assertEquals(EnrichmentStatus.INVALID_IRI, out.get(2).getEnrichmentStatus());
        assertEquals(EnrichmentStatus.SKIPPED_NON_FRAGMENT, out.get(3).getEnrichmentStatus());
    }

    @Test
    void buildResolvedSources_legacyHost_originalPreservedCanonicalIris() {
        List<ResolvedLegalSourceDto> out = OntologyDetailExtractor.buildResolvedSources(List.of(LEGACY_FRAGMENT));

        assertEquals(1, out.size());
        ResolvedLegalSourceDto dto = out.get(0);
        assertEquals(EnrichmentStatus.PENDING, dto.getEnrichmentStatus());
        assertEquals(LEGACY_FRAGMENT, dto.getOriginalUrl());
        assertTrue(dto.getLawIri().startsWith("https://opendata.eselpoint.gov.cz/esel-esb/"));
        assertTrue(dto.getFragmentIri().startsWith("https://opendata.eselpoint.gov.cz/esel-esb/"));
    }
}
