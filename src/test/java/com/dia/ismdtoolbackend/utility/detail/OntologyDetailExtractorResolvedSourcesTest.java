package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto.EnrichmentStatus;
import com.dia.ismdtoolbackend.utility.eli.ParsedEli;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OntologyDetailExtractorResolvedSourcesTest {

    private static final String LAW = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2000/361";
    private static final String VERSION = LAW + "/2024-04-01";
    private static final String FRAGMENT = VERSION + "/dokument/norma/par_2/pism_d";
    private static final String LEGACY_FRAGMENT =
            "https://opendata.eselpoint.cz/esel-esb/eli/cz/sb/2000/361/2024-04-01/dokument/norma/par_5";

    @Test
    void buildResolvedSources_nullInput_returnsEmptyList() {
        assertThat(OntologyDetailExtractor.buildResolvedSources(null))
                .as("stable contract: empty list, never null, so the field serializes as []")
                .isEmpty();
    }

    @Test
    void buildResolvedSources_emptyInput_returnsEmptyList() {
        assertThat(OntologyDetailExtractor.buildResolvedSources(List.of()))
                .as("stable contract: empty list, never null, so the field serializes as []")
                .isEmpty();
    }

    @Test
    void buildResolvedSources_lawUrl_skippedNonFragment() {
        List<ResolvedLegalSourceDto> out = OntologyDetailExtractor.buildResolvedSources(List.of(LAW));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getEnrichmentStatus()).isEqualTo(EnrichmentStatus.SKIPPED_NON_FRAGMENT);
        assertThat(out.get(0).getLevel()).isEqualTo(ParsedEli.Level.LAW);
        assertThat(out.get(0).getDisplayLabel())
                .as("extractor populates parse-only displayLabel via EsbirkaCzechCitationFormatter")
                .isEqualTo("Zákon č. 361/2000 Sb.");
    }

    @Test
    void buildResolvedSources_fragmentUrl_pendingHasFallbackDisplayLabel() {
        List<ResolvedLegalSourceDto> out = OntologyDetailExtractor.buildResolvedSources(List.of(FRAGMENT));
        assertThat(out.get(0).getEnrichmentStatus()).isEqualTo(EnrichmentStatus.PENDING);
        assertThat(out.get(0).getDisplayLabel())
                .as("fragment fallback uses segment-derived citation")
                .isEqualTo("Zákon č. 361/2000 Sb., § 2 písm. d) (znění od 1. 4. 2024)");
    }

    @Test
    void buildResolvedSources_fragmentUrl_pending() {
        List<ResolvedLegalSourceDto> out = OntologyDetailExtractor.buildResolvedSources(List.of(FRAGMENT));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getEnrichmentStatus()).isEqualTo(EnrichmentStatus.PENDING);
        assertThat(out.get(0).getLevel()).isEqualTo(ParsedEli.Level.FRAGMENT);
        assertThat(out.get(0).getFragmentCitation()).as("extractor never calls SPARQL").isNull();
    }

    @Test
    void buildResolvedSources_invalidUrl_invalidIri() {
        List<ResolvedLegalSourceDto> out = OntologyDetailExtractor.buildResolvedSources(List.of("garbage"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getEnrichmentStatus()).isEqualTo(EnrichmentStatus.INVALID_IRI);
        assertThat(out.get(0).getOriginalUrl()).isEqualTo("garbage");
    }

    @Test
    void buildResolvedSources_fragmentSegments_neverNull() {
        // INVALID_IRI builds the DTO without touching fragmentSegments — getter must still
        // return an empty list, never null, so the field always serializes as [].
        ResolvedLegalSourceDto invalid =
                OntologyDetailExtractor.buildResolvedSources(List.of("garbage")).get(0);
        assertThat(invalid.getFragmentSegments())
                .as("fragmentSegments must be empty, never null, even for INVALID_IRI")
                .isEmpty();

        // non-fragment parse leaves no segments either
        ResolvedLegalSourceDto law =
                OntologyDetailExtractor.buildResolvedSources(List.of(LAW)).get(0);
        assertThat(law.getFragmentSegments())
                .as("fragmentSegments must be empty, never null, for non-fragment URLs")
                .isEmpty();
    }

    @Test
    void buildResolvedSources_mixedList_preservesOrderAndStatuses() {
        List<ResolvedLegalSourceDto> out = OntologyDetailExtractor.buildResolvedSources(
                List.of(LAW, FRAGMENT, "junk", VERSION));

        assertThat(out).hasSize(4);
        assertThat(out.get(0).getEnrichmentStatus()).isEqualTo(EnrichmentStatus.SKIPPED_NON_FRAGMENT);
        assertThat(out.get(1).getEnrichmentStatus()).isEqualTo(EnrichmentStatus.PENDING);
        assertThat(out.get(2).getEnrichmentStatus()).isEqualTo(EnrichmentStatus.INVALID_IRI);
        assertThat(out.get(3).getEnrichmentStatus()).isEqualTo(EnrichmentStatus.SKIPPED_NON_FRAGMENT);
    }

    @Test
    void buildResolvedSources_legacyHost_originalPreservedCanonicalIris() {
        List<ResolvedLegalSourceDto> out = OntologyDetailExtractor.buildResolvedSources(List.of(LEGACY_FRAGMENT));

        assertThat(out).hasSize(1);
        ResolvedLegalSourceDto dto = out.get(0);
        assertThat(dto.getEnrichmentStatus()).isEqualTo(EnrichmentStatus.PENDING);
        assertThat(dto.getOriginalUrl()).isEqualTo(LEGACY_FRAGMENT);
        assertThat(dto.getLawIri()).startsWith("https://opendata.eselpoint.gov.cz/esel-esb/");
        assertThat(dto.getFragmentIri()).startsWith("https://opendata.eselpoint.gov.cz/esel-esb/");
    }
}
