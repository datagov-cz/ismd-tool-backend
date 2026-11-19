package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.controller.dto.GetNkodDatasetDto;
import com.dia.ismdtoolbackend.controller.dto.MinimalConceptDto;
import com.dia.ismdtoolbackend.controller.dto.NkodDatasetListDto;
import com.dia.ismdtoolbackend.controller.dto.NkodDatasetListItemDto;
import com.dia.ismdtoolbackend.exception.NkdResourceNotFoundException;
import com.dia.ismdtoolbackend.service.nkod.NkodDatasetService;
import com.dia.ismdtoolbackend.testsupport.web.ControllerSliceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ControllerSliceTest(controllers = NkodDatasetController.class)
class NkodDatasetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NkodDatasetService nkodDatasetService;

    /** Without parameters the list asks for the first page of 20 in Czech, with no filter. */
    @Test
    void listDatasets_appliesDefaults() throws Exception {
        when(nkodDatasetService.listDatasets(null, 20, 0, "cs")).thenReturn(new NkodDatasetListDto(
                List.of(NkodDatasetListItemDto.builder().iri("urn:a").name(Map.of("cs", "Adresy")).build()),
                42));

        mockMvc.perform(get("/api/nkod/dataset/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Seznam datových sad byl úspěšně načten."))
                .andExpect(jsonPath("$.data['celkový-počet']").value(42))
                .andExpect(jsonPath("$.data.datasets.length()").value(1))
                .andExpect(jsonPath("$.data.datasets[0].iri").value("urn:a"))
                .andExpect(jsonPath("$.data.datasets[0]['název'].cs").value("Adresy"));

        verify(nkodDatasetService).listDatasets(null, 20, 0, "cs");
    }

    /** Every parameter reaches the service as sent. */
    @Test
    void listDatasets_passesParametersThrough() throws Exception {
        when(nkodDatasetService.listDatasets("registr", 50, 100, "en"))
                .thenReturn(new NkodDatasetListDto(List.of(), 0));

        mockMvc.perform(get("/api/nkod/dataset/all")
                        .param("q", "registr")
                        .param("limit", "50")
                        .param("offset", "100")
                        .param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data['celkový-počet']").value(0))
                .andExpect(jsonPath("$.data.datasets").isEmpty());

        verify(nkodDatasetService).listDatasets("registr", 50, 100, "en");
    }

    @Test
    void listDatasets_nonNumericLimit_isRejected() throws Exception {
        mockMvc.perform(get("/api/nkod/dataset/all").param("limit", "many"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(nkodDatasetService);
    }

    @Test
    void detail_returnsConceptsForDataset() throws Exception {
        when(nkodDatasetService.getDatasetDetail("urn:ds")).thenReturn(GetNkodDatasetDto.builder()
                .iri("urn:ds")
                .name(Map.of("cs", "Registr řidičů"))
                .concepts(List.of(MinimalConceptDto.builder().iri("urn:pojem").build()))
                .conceptCount(1)
                .build());

        mockMvc.perform(get("/api/nkod/dataset/detail").param("iri", "urn:ds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Detail datové sady byl úspěšně načten."))
                .andExpect(jsonPath("$.data.iri").value("urn:ds"))
                .andExpect(jsonPath("$.data['název'].cs").value("Registr řidičů"))
                .andExpect(jsonPath("$.data.pojmy.length()").value(1))
                .andExpect(jsonPath("$.data.pojmy[0].iri").value("urn:pojem"))
                .andExpect(jsonPath("$.data['počet-pojmů']").value(1));
    }

    /** Until publishers annotate datasets, an empty concept list is a valid 200. */
    @Test
    void detail_emptyConceptList_isOk() throws Exception {
        when(nkodDatasetService.getDatasetDetail("urn:ds")).thenReturn(GetNkodDatasetDto.builder()
                .iri("urn:ds").concepts(List.of()).conceptCount(0).build());

        mockMvc.perform(get("/api/nkod/dataset/detail").param("iri", "urn:ds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pojmy").isEmpty())
                .andExpect(jsonPath("$.data['počet-pojmů']").value(0));
    }

    @Test
    void detail_notFound_mapsTo404() throws Exception {
        when(nkodDatasetService.getDatasetDetail("urn:missing"))
                .thenThrow(new NkdResourceNotFoundException("Datová sada urn:missing nebyla nalezena."));

        mockMvc.perform(get("/api/nkod/dataset/detail").param("iri", "urn:missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("NKD_RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Datová sada urn:missing nebyla nalezena."))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void detail_missingIri_isRejected() throws Exception {
        mockMvc.perform(get("/api/nkod/dataset/detail"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_PARAMETER"));

        verifyNoInteractions(nkodDatasetService);
    }

    /** The concept IRI reaches the service verbatim; a re-encoded IRI would match nothing in the raw-UTF-8 query. */
    @Test
    void byConcept_passesIriThroughVerbatim() throws Exception {
        String conceptIri = "https://slovník.gov.cz/generický/pojem/číslo";
        when(nkodDatasetService.listDatasetsByConcept(conceptIri)).thenReturn(new NkodDatasetListDto(
                List.of(NkodDatasetListItemDto.builder().iri("urn:a").name(Map.of("cs", "Adresy")).build()), 1));

        mockMvc.perform(get("/api/nkod/concept/datasets").param("iri", conceptIri))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Datové sady propojené s pojmem byly úspěšně načteny."))
                .andExpect(jsonPath("$.data['celkový-počet']").value(1))
                .andExpect(jsonPath("$.data.datasets[0].iri").value("urn:a"));

        verify(nkodDatasetService).listDatasetsByConcept(conceptIri);
    }

    /** An unannotated concept is a 200 with an empty list, not a 404. */
    @Test
    void byConcept_noAnnotations_isOkWithEmptyList() throws Exception {
        when(nkodDatasetService.listDatasetsByConcept("urn:concept"))
                .thenReturn(new NkodDatasetListDto(List.of(), 0));

        mockMvc.perform(get("/api/nkod/concept/datasets").param("iri", "urn:concept"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.datasets").isEmpty())
                .andExpect(jsonPath("$.data['celkový-počet']").value(0));
    }
}
