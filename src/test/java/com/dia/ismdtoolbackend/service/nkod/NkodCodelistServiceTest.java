package com.dia.ismdtoolbackend.service.nkod;

import com.dia.ismdtoolbackend.controller.dto.CodeListDto;
import com.dia.ismdtoolbackend.controller.dto.NkodCodelistCheckDto;
import com.dia.ismdtoolbackend.enums.NkodCodelistStatus;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.models.nkod.NkodCodelist;
import com.dia.ismdtoolbackend.models.nkod.NkodCodelistEntry;
import com.dia.ismdtoolbackend.models.nkod.NkodCodelistSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NkodCodelistServiceTest {

    private static final String POHLAVI = "https://data.gov.cz/zdroj/datové-sady/17651921/5ccc4289";
    private static final String POHLAVI_2025 = "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/číselníky/151/2025-01-01";
    private static final String POHLAVI_2024 = "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/číselníky/151/2024-01-01";
    private static final String AACJ = "https://data.gov.cz/zdroj/datové-sady/00022985/aacj";
    private static final String ISVAV = "https://data.gov.cz/zdroj/datové-sady/00216208/102d4d07";

    @Mock
    private NkodCodelistSnapshotHolder holder;

    @InjectMocks
    private NkodCodelistService service;

    private static NkodCodelistEntry entry(String datasetIri, String title, String codeListIri) {
        return new NkodCodelistEntry(NkodCodelist.builder()
                .datasetIri(datasetIri).codeListIri(codeListIri).title(title).publisher("DIA").build(),
                List.of("https://x/" + title + ".jsonld"));
    }

    /** Pohlaví resolved, Jazyk (MŠMT) unresolved, ISVaV resolved — in title order. */
    private static NkodCodelistSnapshot snapshot() {
        return NkodCodelistSnapshot.of(Instant.parse("2026-09-30T10:00:00Z"), List.of(
                entry(AACJ, "Jazyk", null),
                entry(ISVAV, "Klasifikace oborů ISVaV", "https://data.mff.cuni.cz/zdroj/číselníky/isvav"),
                entry(POHLAVI, "Pohlaví", POHLAVI_2025)));
    }

    private static CodeListDto stored(String datasetIri, String codeListIri) {
        return CodeListDto.builder().iri(codeListIri).datovaSadaVNkod(datasetIri).build();
    }

    private Optional<NkodCodelistCheckDto> check(CodeListDto stored) {
        when(holder.peek()).thenReturn(snapshot());
        return service.check(stored);
    }

    @Test
    void list_returnsOnlyResolvedEntries_inSnapshotOrder() {
        when(holder.get()).thenReturn(snapshot());

        assertThat(service.list(null)).extracting(NkodCodelist::getDatasetIri).containsExactly(ISVAV, POHLAVI);
        assertThat(service.list(null)).allSatisfy(c -> assertThat(c.getCodeListIri()).isNotBlank());
    }

    @Test
    void list_withQuery_returnsOnlyMatchingResolvedEntries() {
        when(holder.get()).thenReturn(snapshot());

        assertThat(service.list("pohlavi")).extracting(NkodCodelist::getDatasetIri).containsExactly(POHLAVI);
        // "Jazyk" matches but has no codelist IRI, so it stays hidden.
        assertThat(service.list("jazyk")).isEmpty();
    }

    @Test
    void list_propagatesUnavailableWhenNothingLoaded() {
        when(holder.get()).thenThrow(new SparqlEndpointUnavailableException("NKOD", "down"));

        assertThatThrownBy(() -> service.list(null)).isInstanceOf(SparqlEndpointUnavailableException.class);
    }

    @Test
    void check_sameIri_isCurrent() {
        NkodCodelistCheckDto result = check(stored(POHLAVI, POHLAVI_2025)).orElseThrow();

        assertThat(result.getStatus()).isEqualTo(NkodCodelistStatus.CURRENT);
        assertThat(result.getCodelist().getTitle()).isEqualTo("Pohlaví");
    }

    @Test
    void check_differentIri_isNewVersion_carryingTheIriToSave() {
        NkodCodelistCheckDto result = check(stored(POHLAVI, POHLAVI_2024)).orElseThrow();

        assertThat(result.getStatus()).isEqualTo(NkodCodelistStatus.NEW_VERSION);
        assertThat(result.getCodelist().getCodeListIri()).isEqualTo(POHLAVI_2025);
        assertThat(result.getCodelist().getDatasetIri()).isEqualTo(POHLAVI);
    }

    @Test
    void check_datasetNotInTheCatalogue_isMissing_withoutCodelist() {
        NkodCodelistCheckDto result =
                check(stored("https://data.gov.cz/zdroj/datové-sady/x/gone", POHLAVI_2025)).orElseThrow();

        assertThat(result.getStatus()).isEqualTo(NkodCodelistStatus.MISSING);
        assertThat(result.getCodelist()).isNull();
    }

    @Test
    void check_datasetInTheCatalogueButUnresolved_isOmitted() {
        assertThat(check(stored(AACJ, "https://msmt/ciselnik/aacj"))).isEmpty();
    }

    @Test
    void check_snapshotNeverLoaded_isOmitted_notMissing() {
        when(holder.peek()).thenReturn(NkodCodelistSnapshot.notLoaded());

        assertThat(service.check(stored(POHLAVI, POHLAVI_2025))).isEmpty();
        verify(holder, never()).get();
    }

    @Test
    void check_snapshotLoadedWithNoDatasets_isMissing() {
        when(holder.peek()).thenReturn(NkodCodelistSnapshot.of(Instant.parse("2026-09-30T10:00:00Z"), List.of()));

        assertThat(service.check(stored(POHLAVI, POHLAVI_2025)))
                .map(NkodCodelistCheckDto::getStatus).contains(NkodCodelistStatus.MISSING);
    }

    @Test
    void check_percentEncodedStoredIris_compareAsRaw() {
        CodeListDto encoded = stored(
                POHLAVI.replace("datové-sady", "datov%C3%A9-sady"),
                POHLAVI_2025.replace("číselníky", "%C4%8D%C3%ADseln%C3%ADky"));

        assertThat(check(encoded)).map(NkodCodelistCheckDto::getStatus).contains(NkodCodelistStatus.CURRENT);
    }

    @Test
    void check_storedIriMissing_isNewVersion() {
        assertThat(check(stored(POHLAVI, null)))
                .map(NkodCodelistCheckDto::getStatus).contains(NkodCodelistStatus.NEW_VERSION);
    }

    @Test
    void check_noCodelistOrNoDataset_isOmittedWithoutTouchingTheHolder() {
        assertThat(service.check(null)).isEmpty();
        assertThat(service.check(stored(null, POHLAVI_2025))).isEmpty();
        assertThat(service.check(stored(" ", POHLAVI_2025))).isEmpty();

        verifyNoInteractions(holder);
    }
}
