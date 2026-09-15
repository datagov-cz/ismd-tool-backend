package com.dia.ismdtoolbackend.service.nkod;

import com.dia.ismdtoolbackend.client.NkodSparqlClient;
import com.dia.ismdtoolbackend.config.NkodConfig;
import com.dia.ismdtoolbackend.controller.dto.GetNkodDatasetDto;
import com.dia.ismdtoolbackend.controller.dto.NkodDatasetListDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.exception.NkdResourceNotFoundException;
import com.dia.ismdtoolbackend.controller.dto.NkodDistributionDto;
import com.dia.ismdtoolbackend.models.nkod.NkodDatasetDetail;
import com.dia.ismdtoolbackend.models.nkod.NkodDistribution;
import com.dia.ismdtoolbackend.models.nkod.NkodDatasetRow;
import com.dia.ismdtoolbackend.models.nkod.NkodDatasetSnapshot;
import com.dia.ismdtoolbackend.service.impl.ReferencedConceptResolutionEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NkodDatasetServiceImplTest {

    @Mock
    private NkodDatasetSnapshotHolder snapshotHolder;
    @Mock
    private NkodSparqlClient client;
    @Mock
    private ReferencedConceptResolutionEngine resolutionEngine;

    private NkodDatasetServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NkodDatasetServiceImpl(snapshotHolder, client, resolutionEngine, new NkodConfig());
    }

    private static NkodDatasetRow row(String iri, String title) {
        return new NkodDatasetRow(iri, Map.of("cs", title), Map.of());
    }

    private void givenCatalogue(NkodDatasetRow... rows) {
        when(snapshotHolder.get()).thenReturn(
                NkodDatasetSnapshot.build(Instant.parse("2026-09-15T10:00:00Z"), List.of(rows), "cs"));
    }

    @Test
    void listReturnsRequestedPage() {
        givenCatalogue(row("urn:a", "Alfa"), row("urn:b", "Beta"), row("urn:c", "Gama"));

        NkodDatasetListDto page = service.listDatasets(null, 2, 0, "cs");

        assertThat(page.getDatasets()).extracting(d -> d.getIri()).containsExactly("urn:a", "urn:b");
    }

    @Test
    void listAppliesOffset() {
        givenCatalogue(row("urn:a", "Alfa"), row("urn:b", "Beta"), row("urn:c", "Gama"));

        NkodDatasetListDto page = service.listDatasets(null, 2, 2, "cs");

        assertThat(page.getDatasets()).extracting(d -> d.getIri()).containsExactly("urn:c");
    }

    /** Total must describe the whole match set, not the page, or the FE cannot page. */
    @Test
    void totalCountReflectsAllMatchesNotPageSize() {
        givenCatalogue(row("urn:a", "Alfa"), row("urn:b", "Beta"), row("urn:c", "Gama"));

        NkodDatasetListDto page = service.listDatasets(null, 1, 0, "cs");

        assertThat(page.getDatasets()).hasSize(1);
        assertThat(page.getTotalCount()).isEqualTo(3);
    }

    @Test
    void totalCountReflectsFilteredMatches() {
        givenCatalogue(row("urn:a", "Registr řidičů"), row("urn:b", "Adresy"));

        NkodDatasetListDto page = service.listDatasets("registr", 20, 0, "cs");

        assertThat(page.getTotalCount()).isEqualTo(1);
    }

    /**
     * Guards the actual defect: the service accepted lang and never passed it on, so every
     * language returned Czech order. The snapshot-level test cannot catch that — only calling
     * through the service can.
     */
    @Test
    void listSortsByRequestedLanguage() {
        when(snapshotHolder.get()).thenReturn(NkodDatasetSnapshot.build(
                Instant.parse("2026-09-15T10:00:00Z"),
                List.of(new NkodDatasetRow("urn:a", Map.of("cs", "Adresy", "en", "Zebra"), Map.of()),
                        new NkodDatasetRow("urn:b", Map.of("cs", "Zvířata", "en", "Addresses"), Map.of())),
                "cs"));

        NkodDatasetListDto page = service.listDatasets(null, 20, 0, "en");

        assertThat(page.getDatasets()).extracting(d -> d.getIri())
                .containsExactly("urn:b", "urn:a");
    }

    @Test
    void blankLanguageFallsBackToDefault() {
        givenCatalogue(row("urn:b", "Beta"), row("urn:a", "Alfa"));

        NkodDatasetListDto page = service.listDatasets(null, 20, 0, "  ");

        assertThat(page.getDatasets()).extracting(d -> d.getIri())
                .containsExactly("urn:a", "urn:b");
    }

    @Test
    void limitIsClampedToConfiguredMaximum() {
        givenCatalogue(row("urn:a", "Alfa"));

        NkodDatasetListDto page = service.listDatasets(null, 99999, 0, "cs");

        assertThat(page.getDatasets()).hasSize(1);
    }

    @Test
    void negativeOffsetIsTreatedAsZero() {
        givenCatalogue(row("urn:a", "Alfa"), row("urn:b", "Beta"));

        NkodDatasetListDto page = service.listDatasets(null, 1, -5, "cs");

        assertThat(page.getDatasets()).extracting(d -> d.getIri()).containsExactly("urn:a");
    }

    @Test
    void detailResolvesConceptNames() {
        when(client.fetchDatasetDetail("urn:ds")).thenReturn(Optional.of(new NkodDatasetDetail(
                "urn:ds", Map.of("cs", "Registr"), Map.of(),
                List.of("urn:pojem"), List.of())));
        when(resolutionEngine.resolveAll(anyList(), eq(SearchSource.NKD))).thenReturn(Map.of(
                "urn:pojem", ResolvedConceptDto.builder()
                        .iri("urn:pojem")
                        .conceptName(Map.of("cs", "Řidič"))
                        .build()));

        GetNkodDatasetDto dto = service.getDatasetDetail("urn:ds");

        assertThat(dto.getConcepts()).hasSize(1);
        assertThat(dto.getConcepts().get(0).getName()).containsEntry("cs", "Řidič");
        assertThat(dto.getConceptCount()).isEqualTo(1);
    }

    /** An unresolvable concept is still listed by IRI rather than silently dropped. */
    @Test
    void detailKeepsUnresolvedConceptsAsIriOnlyRows() {
        when(client.fetchDatasetDetail("urn:ds")).thenReturn(Optional.of(new NkodDatasetDetail(
                "urn:ds", Map.of(), Map.of(), List.of("urn:unknown"), List.of())));
        when(resolutionEngine.resolveAll(anyList(), eq(SearchSource.NKD))).thenReturn(Map.of());

        GetNkodDatasetDto dto = service.getDatasetDetail("urn:ds");

        assertThat(dto.getConcepts()).hasSize(1);
        assertThat(dto.getConcepts().get(0).getIri()).isEqualTo("urn:unknown");
        assertThat(dto.getConcepts().get(0).getName()).isNull();
    }

    /** Expected until publishers populate týká-se-pojmu: empty, and no pointless round-trip. */
    @Test
    void detailWithNoConceptsSkipsResolution() {
        when(client.fetchDatasetDetail("urn:ds")).thenReturn(Optional.of(new NkodDatasetDetail(
                "urn:ds", Map.of("cs", "Prázdná"), Map.of(), List.of(), List.of())));

        GetNkodDatasetDto dto = service.getDatasetDetail("urn:ds");

        assertThat(dto.getConcepts()).isEmpty();
        assertThat(dto.getConceptCount()).isZero();
        verify(resolutionEngine, never()).resolveAll(anyList(), eq(SearchSource.NKD));
    }

    private static NkodDatasetDetail detailWith(NkodDistribution... distributions) {
        return new NkodDatasetDetail("urn:ds", Map.of("cs", "Sada"), Map.of(),
                List.of(), List.of(distributions));
    }

    /**
     * The whole point of the single {@code odkaz} field: downloadURL wins, and the
     * near-always-identical accessURL is not surfaced a second time.
     */
    @Test
    void distributionPrefersDownloadUrlOverAccessUrl() {
        when(client.fetchDatasetDetail("urn:ds")).thenReturn(Optional.of(detailWith(
                new NkodDistribution("urn:d1", Map.of("cs", "CSV"), "https://host/data.csv",
                        "urn:fmt:CSV", "urn:mt:csv", false))));

        GetNkodDatasetDto dto = service.getDatasetDetail("urn:ds");

        assertThat(dto.getDistributions()).hasSize(1);
        assertThat(dto.getDistributions().get(0).getLink()).isEqualTo("https://host/data.csv");
        assertThat(dto.getDistributions().get(0).isSluzba()).isFalse();
    }

    /** An API/WMS/SPARQL distribution must reach the FE flagged, so it reads "Otevřít". */
    @Test
    void serviceDistributionIsFlagged() {
        when(client.fetchDatasetDetail("urn:ds")).thenReturn(Optional.of(detailWith(
                new NkodDistribution("urn:d1", Map.of(), "https://host/sparql",
                        null, null, true))));

        GetNkodDatasetDto dto = service.getDatasetDetail("urn:ds");

        assertThat(dto.getDistributions()).hasSize(1);
        assertThat(dto.getDistributions().get(0).isSluzba()).isTrue();
    }

    /** Neither URL published — there is nothing to link to, so the row is dropped. */
    @Test
    void distributionWithoutAnyLinkIsDropped() {
        when(client.fetchDatasetDetail("urn:ds")).thenReturn(Optional.of(detailWith(
                new NkodDistribution("urn:d1", Map.of("cs", "Bez odkazu"), null, null, null, false),
                new NkodDistribution("urn:d2", Map.of(), "https://host/ok.csv", null, null, false))));

        GetNkodDatasetDto dto = service.getDatasetDetail("urn:ds");

        assertThat(dto.getDistributions()).extracting(NkodDistributionDto::getIri)
                .containsExactly("urn:d2");
    }

    /** A dataset with no distributions serializes an empty list, never a missing field. */
    @Test
    void datasetWithNoDistributionsYieldsEmptyList() {
        when(client.fetchDatasetDetail("urn:ds")).thenReturn(Optional.of(detailWith()));

        GetNkodDatasetDto dto = service.getDatasetDetail("urn:ds");

        assertThat(dto.getDistributions()).isNotNull().isEmpty();
    }

    @Test
    void detailThrowsWhenDatasetAbsent() {
        when(client.fetchDatasetDetail(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDatasetDetail("urn:missing"))
                .isInstanceOf(NkdResourceNotFoundException.class);
    }

    @Test
    void detailRejectsBlankIri() {
        assertThatThrownBy(() -> service.getDatasetDetail("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}