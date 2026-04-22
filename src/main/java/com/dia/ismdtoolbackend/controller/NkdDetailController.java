package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdConceptDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyDto;
import com.dia.ismdtoolbackend.service.NkdDetailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

import static com.dia.constants.FormatConstants.Converter.LOG_REQUEST_ID;

@RestController
@RequestMapping("/api/nkd")
@RequiredArgsConstructor
@Slf4j
public class NkdDetailController {

    private final NkdDetailService nkdDetailService;

    @Operation(
            summary = "Detail slovníku z NKD",
            description = "Vrací kompletní detail slovníku publikovaného v Národním katalogu dat (NKD) podle IRI zdroje. Detail je sestaven ze SPARQL CONSTRUCT dotazu na NKD endpoint a obsahuje metadata slovníku včetně všech jeho pojmů. Veřejný endpoint."
    )
    @GetMapping("/ontology/detail")
    public ResponseEntity<ApiResponseDto<GetNkdOntologyDto>> getNkdOntologyDetail(
            @Parameter(description = "IRI slovníku v NKD", required = true)
            @RequestParam String iri
    ) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("NKD ontology detail requested, iri: {}", iri);

        GetNkdOntologyDto dto = nkdDetailService.getOntologyDetail(iri);

        return ResponseEntity.ok()
                .body(ApiResponseDto.success(dto, "Detail slovníku z NKD byl úspěšně načten."));
    }

    @Operation(
            summary = "Detail pojmu z NKD",
            description = "Vrací kompletní detail pojmu publikovaného v Národním katalogu dat (NKD) podle IRI zdroje. Detail je sestaven ze SPARQL CONSTRUCT dotazu na NKD endpoint. Parametr ontologyIri je volitelný a slouží jako kontext pro frontend (např. drobečková navigace). Veřejný endpoint."
    )
    @GetMapping("/concept/detail")
    public ResponseEntity<ApiResponseDto<GetNkdConceptDto>> getNkdConceptDetail(
            @Parameter(description = "IRI pojmu v NKD", required = true)
            @RequestParam String iri,
            @Parameter(description = "IRI slovníku, do kterého pojem patří (volitelné)")
            @RequestParam(required = false) String ontologyIri
    ) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("NKD concept detail requested, iri: {}, ontologyIri: {}", iri, ontologyIri);

        GetNkdConceptDto dto = nkdDetailService.getConceptDetail(iri, ontologyIri);

        return ResponseEntity.ok()
                .body(ApiResponseDto.success(dto, "Detail pojmu z NKD byl úspěšně načten."));
    }
}
