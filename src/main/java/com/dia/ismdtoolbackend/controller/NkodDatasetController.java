package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkodDatasetDto;
import com.dia.ismdtoolbackend.controller.dto.NkodDatasetListDto;
import com.dia.ismdtoolbackend.service.nkod.NkodDatasetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/nkod")
@RequiredArgsConstructor
@Slf4j
public class NkodDatasetController {

    private final NkodDatasetService nkodDatasetService;

    @Operation(
            summary = "Seznam datových sad z NKOD (stránkovaně)",
            description = "Vrací stránkovaný seznam datových sad z Národního katalogu otevřených dat (NKOD), "
                    + "seřazený abecedně podle názvu. Volitelný parametr q filtruje datové sady podle názvu "
                    + "a popisu; vyhledávání nerozlišuje diakritiku ani velikost písmen. Seznam je obsluhován "
                    + "z lokální kopie katalogu, která se obnovuje na pozadí. Veřejný endpoint."
    )
    @GetMapping("/dataset/all")
    public ResponseEntity<ApiResponseDto<NkodDatasetListDto>> listDatasets(
            @Parameter(description = "Hledaný výraz v názvu a popisu datové sady")
            @RequestParam(required = false) String q,
            @Parameter(description = "Maximální počet výsledků na stránku (1-100)")
            @RequestParam(defaultValue = "20") int limit,
            @Parameter(description = "Offset pro stránkování")
            @RequestParam(defaultValue = "0") int offset,
            @Parameter(description = "Jazyk pro řazení a zobrazení názvu (výchozí cs)")
            @RequestParam(defaultValue = "cs") String lang
    ) {
        log.info("NKOD dataset list requested, q={}, limit={}, offset={}, lang={}", q, limit, offset, lang);

        NkodDatasetListDto dto = nkodDatasetService.listDatasets(q, limit, offset, lang);

        return ResponseEntity.ok()
                .body(ApiResponseDto.success(dto, "Seznam datových sad byl úspěšně načten."));
    }

    @Operation(
            summary = "Detail datové sady z NKOD",
            description = "Vrací detail datové sady z Národního katalogu otevřených dat (NKOD) podle IRI, "
                    + "včetně seznamu pojmů, kterými je datová sada anotována (týká se pojmu), "
                    + "a seznamu distribucí. Seznam pojmů může být prázdný, pokud datová sada "
                    + "anotace zatím neobsahuje. Každá distribuce nese jeden odkaz; příznak "
                    + "je-služba rozlišuje soubor ke stažení od API či mapové služby. "
                    + "Veřejný endpoint."
    )
    @GetMapping("/dataset/detail")
    public ResponseEntity<ApiResponseDto<GetNkodDatasetDto>> getDatasetDetail(
            @Parameter(description = "IRI datové sady v NKOD", required = true)
            @RequestParam String iri
    ) {
        log.info("NKOD dataset detail requested, iri: {}", iri);

        GetNkodDatasetDto dto = nkodDatasetService.getDatasetDetail(iri);

        return ResponseEntity.ok()
                .body(ApiResponseDto.success(dto, "Detail datové sady byl úspěšně načten."));
    }
}