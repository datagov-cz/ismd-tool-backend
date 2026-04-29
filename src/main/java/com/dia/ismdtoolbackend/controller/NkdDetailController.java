package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdConceptDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyListDto;
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
import java.util.List;
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
            summary = "Seznam slovníků z NKD podle IRI",
            description = "Vrací zkrácené metadata slovníků publikovaných v Národním katalogu dat (NKD) " +
                    "pro zadaný seznam IRI. Slouží například k zobrazení naposledy navštívených slovníků, " +
                    "kde frontend uchovává seznam IRI v localStorage. IRI, které se nepodaří načíst nebo " +
                    "v NKD neexistují, jsou v odpovědi vynechány. Maximálně 50 IRI v jedné žádosti. " +
                    "Veřejný endpoint."
    )
    @GetMapping("/ontology/list")
    public ResponseEntity<ApiResponseDto<GetNkdOntologyListDto>> getNkdOntologyList(
            @Parameter(description = "Seznam IRI slovníků v NKD (max 50)", required = true)
            @RequestParam List<String> iris
    ) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("NKD ontology list requested, count: {}", iris == null ? 0 : iris.size());

        GetNkdOntologyListDto dto = nkdDetailService.getOntologyList(iris);

        return ResponseEntity.ok()
                .body(ApiResponseDto.success(dto, "Seznam slovníků z NKD byl úspěšně načten."));
    }

    @Operation(
            summary = "Seznam všech slovníků z NKD (stránkovaně)",
            description = "Vrací stránkovaný seznam všech slovníků publikovaných v Národním katalogu dat (NKD), " +
                    "seřazený abecedně podle názvu v zadaném jazyce (výchozí cs). Každá položka obsahuje " +
                    "základní metadata slovníku a počet jeho pojmů. Odpověď dále obsahuje agregované hodnoty: " +
                    "celkový počet slovníků a celkový počet pojmů napříč NKD. Veřejný endpoint."
    )
    @GetMapping("/ontology/all")
    public ResponseEntity<ApiResponseDto<GetNkdOntologyListDto>> listAllNkdOntologies(
            @Parameter(description = "Maximální počet výsledků na stránku (1-100)")
            @RequestParam(defaultValue = "20") int limit,
            @Parameter(description = "Offset pro stránkování")
            @RequestParam(defaultValue = "0") int offset,
            @Parameter(description = "Jazyk pro řazení podle názvu (výchozí cs)")
            @RequestParam(defaultValue = "cs") String lang
    ) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        try {
            log.info("NKD ontology list-all requested, limit={}, offset={}, lang={}", limit, offset, lang);

            GetNkdOntologyListDto dto = nkdDetailService.listAllOntologies(limit, offset, lang);

            return ResponseEntity.ok()
                    .body(ApiResponseDto.success(dto, "Seznam slovníků z NKD byl úspěšně načten."));
        } finally {
            MDC.remove(LOG_REQUEST_ID);
        }
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
