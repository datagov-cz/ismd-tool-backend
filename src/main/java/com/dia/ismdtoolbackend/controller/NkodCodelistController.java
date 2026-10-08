package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.models.nkod.NkodCodelist;
import com.dia.ismdtoolbackend.service.nkod.NkodCodelistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The číselník picker: codelist datasets published in NKOD. Public, no user context. */
@RestController
@RequestMapping("/api/codelist/nkod")
@RequiredArgsConstructor
@Slf4j
public class NkodCodelistController {

    private final NkodCodelistService nkodCodelistService;

    @Operation(
            summary = "Seznam číselníků z NKOD",
            description = "Vrací číselníky publikované v Národním katalogu otevřených dat (NKOD), "
                    + "seřazené abecedně podle názvu. Volitelný parametr q filtruje číselníky podle názvu "
                    + "a popisu; vyhledávání nerozlišuje diakritiku ani velikost písmen. Každá položka nese IRI datové sady i IRI číselníku, "
                    + "tedy obě hodnoty potřebné pro uložení číselníku k pojmu. Číselníky, u nichž nelze "
                    + "IRI číselníku zjistit, se nevracejí. Seznam je obsluhován z lokální kopie katalogu, "
                    + "která se obnovuje na pozadí. Veřejný endpoint."
    )
    @GetMapping
    public ResponseEntity<ApiResponseDto<List<NkodCodelist>>> list(
            @Parameter(description = "Hledaný výraz v názvu a popisu číselníku")
            @RequestParam(required = false) String q
    ) {
        List<NkodCodelist> codelists = nkodCodelistService.list(q);
        log.info("NKOD codelist list requested, q={}, {} entries", q, codelists.size());

        return ResponseEntity.ok()
                .body(ApiResponseDto.success(codelists, "Seznam číselníků byl úspěšně načten."));
    }
}
