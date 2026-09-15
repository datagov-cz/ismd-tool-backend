package com.dia.ismdtoolbackend.service.nkod;

import com.dia.ismdtoolbackend.client.NkodSparqlClient;
import com.dia.ismdtoolbackend.config.NkodConfig;
import com.dia.ismdtoolbackend.controller.dto.GetNkodDatasetDto;
import com.dia.ismdtoolbackend.controller.dto.NkodDatasetListDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.exception.NkdResourceNotFoundException;
import com.dia.ismdtoolbackend.models.nkod.NkodDatasetDetail;
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
                "urn:ds", Map.of("cs", "Registr"), Map.of(), "https://nkd/ds",
                List.of("urn:pojem"))));
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
                "urn:ds", Map.of(), Map.of(), null, List.of("urn:unknown"))));
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
                "urn:ds", Map.of("cs", "Prázdná"), Map.of(), null, List.of())));

        GetNkodDatasetDto dto = service.getDatasetDetail("urn:ds");

        assertThat(dto.getConcepts()).isEmpty();
        assertThat(dto.getConceptCount()).isZero();
        verify(resolutionEngine, never()).resolveAll(anyList(), eq(SearchSource.NKD));
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