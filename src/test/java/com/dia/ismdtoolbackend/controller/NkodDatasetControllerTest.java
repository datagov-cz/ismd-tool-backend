package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.controller.dto.GetNkodDatasetDto;
import com.dia.ismdtoolbackend.controller.dto.MinimalConceptDto;
import com.dia.ismdtoolbackend.controller.dto.NkodDatasetListDto;
import com.dia.ismdtoolbackend.controller.dto.NkodDatasetListItemDto;
import com.dia.ismdtoolbackend.exception.NkdResourceNotFoundException;
import com.dia.ismdtoolbackend.service.nkod.NkodDatasetService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NkodDatasetControllerTest {

    @Mock
    private NkodDatasetService nkodDatasetService;

    @InjectMocks
    private NkodDatasetController controller;

    @Test
    void listDatasetsReturnsEnvelopeWithTotalCount() {
        NkodDatasetListDto dto = new NkodDatasetListDto(
                List.of(NkodDatasetListItemDto.builder()
                        .iri("urn:a")
                        .name(Map.of("cs", "Adresy"))
                        .build()),
                42);
        when(nkodDatasetService.listDatasets(any(), anyInt(), anyInt(), anyString())).thenReturn(dto);

        ResponseEntity<?> response = controller.listDatasets("adr", 20, 0, "cs");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(dto.getTotalCount()).isEqualTo(42);
    }

    /** The search term must reach the service rather than being silently dropped. */
    @Test
    void listDatasetsPassesQueryThrough() {
        when(nkodDatasetService.listDatasets(any(), anyInt(), anyInt(), anyString()))
                .thenReturn(new NkodDatasetListDto(List.of(), 0));

        controller.listDatasets("registr", 50, 100, "en");

        verify(nkodDatasetService).listDatasets("registr", 50, 100, "en");
    }

    @Test
    void detailReturnsConceptsForDataset() {
        GetNkodDatasetDto dto = GetNkodDatasetDto.builder()
                .iri("urn:ds")
                .name(Map.of("cs", "Registr řidičů"))
                .concepts(List.of(MinimalConceptDto.builder().iri("urn:pojem").build()))
                .conceptCount(1)
                .build();
        when(nkodDatasetService.getDatasetDetail(eq("urn:ds"))).thenReturn(dto);

        ResponseEntity<?> response = controller.getDatasetDetail("urn:ds");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(dto.getConcepts()).hasSize(1);
    }

    /**
     * The expected state until publishers populate the annotation — an empty list is a valid
     * 200, not an error.
     */
    @Test
    void detailReturnsOkWithEmptyConceptList() {
        GetNkodDatasetDto dto = GetNkodDatasetDto.builder()
                .iri("urn:ds")
                .concepts(List.of())
                .conceptCount(0)
                .build();
        when(nkodDatasetService.getDatasetDetail(anyString())).thenReturn(dto);

        ResponseEntity<?> response = controller.getDatasetDetail("urn:ds");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(dto.getConcepts()).isEmpty();
    }

    @Test
    void detailPropagatesNotFound() {
        when(nkodDatasetService.getDatasetDetail(anyString()))
                .thenThrow(new NkdResourceNotFoundException("nenalezeno"));

        assertThatThrownBy(() -> controller.getDatasetDetail("urn:missing"))
                .isInstanceOf(NkdResourceNotFoundException.class);
    }
}