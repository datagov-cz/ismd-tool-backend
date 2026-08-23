package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.EsbirkaConfig;
import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.controller.dto.FragmentDto;
import com.dia.ismdtoolbackend.controller.dto.LawContentDto;
import com.dia.ismdtoolbackend.controller.dto.LawDto;
import com.dia.ismdtoolbackend.controller.dto.LawSearchResultDto;
import com.dia.ismdtoolbackend.controller.dto.LawVersionDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto;
import com.dia.ismdtoolbackend.service.EsbirkaService;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.dia.constants.FormatConstants.Converter.LOG_REQUEST_ID;

@RestController
@RequestMapping("/api/eli")
@RequiredArgsConstructor
@Slf4j
public class EsbirkaController {

    private final EsbirkaService esbirkaService;
    private final EsbirkaConfig esbirkaConfig;

    @Operation(
            summary = "Vyhledávání právních aktů v e-Sbírce",
            description = "Vrací seznam právních aktů filtrovaný podle citace (např. \"187/2006\"). " +
                    "Při prázdném dotazu se vrací nejnovější akty (rok desc, číslo asc)."
    )
    @GetMapping("/law/search")
    public ResponseEntity<ApiResponseDto<List<LawDto>>> searchLaws(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        try {
            int resolved = resolveLimit(limit);
            log.info("e-Sbírka law search, q: {}, limit: {}", q, resolved);
            List<LawDto> results = esbirkaService.searchLaws(q, resolved);
            return ResponseEntity.ok(ApiResponseDto.success(results,
                    "Vyhledávání právních aktů úspěšně provedeno."));
        } finally {
            MDC.remove(LOG_REQUEST_ID);
        }
    }

    @Operation(
            summary = "Vyhledávání právních aktů seskupené podle čísla předpisu",
            description = "Stejné vyhledávání jako /law/search, ale výsledky jsou seskupené podle " +
                    "čísla předpisu. České předpisy se číslují každý rok od jedničky, takže dotaz " +
                    "\"49\" odpovídá desítkám nesouvisejících zákonů (49/1997, 49/2020, 49/2026 …) — " +
                    "plochý seznam je zaplní jedním číslem a hledaný zákon vypadne. " +
                    "Příznak \"ambiguous\" značí, že si uživatel musí ještě vybrat (typicky ročník); " +
                    "\"truncated\" značí, že existují další shody mimo odpověď. " +
                    "Parametr limit omezuje počet skupin, nikoli řádků."
    )
    @GetMapping("/law/search/grouped")
    public ResponseEntity<ApiResponseDto<LawSearchResultDto>> searchLawsGrouped(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        try {
            int resolved = resolveLimit(limit);
            log.info("e-Sbírka grouped law search, q: {}, limit: {}", q, resolved);
            LawSearchResultDto result = esbirkaService.searchLawsGrouped(q, resolved);
            return ResponseEntity.ok(ApiResponseDto.success(result,
                    "Vyhledávání právních aktů úspěšně provedeno."));
        } finally {
            MDC.remove(LOG_REQUEST_ID);
        }
    }

    @Operation(
            summary = "Seznam znění daného právního aktu",
            description = "Vrací všechna znění zadaného právního aktu, řazeno od nejnovějšího; " +
                    "pole \"latest\" označuje aktuálně poslední znění."
    )
    @GetMapping("/law/versions")
    public ResponseEntity<ApiResponseDto<List<LawVersionDto>>> getVersions(
            @RequestParam String lawIri) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        try {
            requireEsbirkaIri(lawIri, "Neplatný identifikátor právního aktu.");
            log.info("e-Sbírka law versions, lawIri: {}", lawIri);
            List<LawVersionDto> results = esbirkaService.getVersions(lawIri);
            return ResponseEntity.ok(ApiResponseDto.success(results,
                    "Seznam znění úspěšně načten."));
        } finally {
            MDC.remove(LOG_REQUEST_ID);
        }
    }

    @Operation(
            summary = "Strom fragmentů daného znění právního aktu",
            description = "Vrací rekurzivní strom fragmentů zadaného znění (jeden SPARQL dotaz, " +
                    "stromová struktura sestavena na serveru)."
    )
    @GetMapping("/law/fragments")
    public ResponseEntity<ApiResponseDto<List<FragmentDto>>> getFragments(
            @RequestParam String versionIri) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        try {
            requireEsbirkaIri(versionIri, "Neplatný identifikátor znění právního aktu.");
            log.info("e-Sbírka fragment tree, versionIri: {}", versionIri);
            List<FragmentDto> results = esbirkaService.getFragments(versionIri);
            return ResponseEntity.ok(ApiResponseDto.success(results,
                    "Strom fragmentů úspěšně načten."));
        } finally {
            MDC.remove(LOG_REQUEST_ID);
        }
    }

    @Operation(
            summary = "Celé znění právního aktu podle reference číslo/rok",
            description = "Přijímá referenci ve tvaru \"číslo/rok\" (např. \"49/1997\"), vyhledá daný " +
                    "právní akt přesnou shodou a vrátí celé jeho znění: hlavičku (IRI aktu, citace, " +
                    "znění, datum účinnosti, příznak posledního znění), seznam všech znění (pro přepínač) " +
                    "a strom fragmentů, kde každý uzel nese své HTML \"obsah\" tělo pro interaktivní " +
                    "procházení a výběr sekcí. Bez parametru \"versionIri\" se vrací poslední znění; " +
                    "s ním se vrací zvolené znění (IRI musí patřit k danému aktu, jinak 400). " +
                    "Pro částečný vstup (např. \"49\") použijte /law/search. " +
                    "Výsledek je cachován pro každé znění zvlášť (znění je neměnné)."
    )
    @GetMapping("/law/content")
    public ResponseEntity<ApiResponseDto<LawContentDto>> getLawContent(
            @RequestParam String law,
            @RequestParam(required = false) String versionIri) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        try {
            log.info("e-Sbírka law content, law: {}, versionIri: {}", law, versionIri);
            LawContentDto result = esbirkaService.getLawContent(law, versionIri);
            return ResponseEntity.ok(ApiResponseDto.success(result,
                    "Celé znění právního aktu úspěšně načteno."));
        } finally {
            MDC.remove(LOG_REQUEST_ID);
        }
    }

    @Operation(
            summary = "Resolve e-Sbírka ELI URL to a display object",
            description = "Parses the URL synchronously, and for fragment-level URLs " +
                    "fetches the official citation + version metadata via SPARQL (cached 24h). " +
                    "Forgiving: invalid URLs return HTTP 200 with enrichmentStatus=INVALID_IRI. " +
                    "Public endpoint — used by concept detail rendering for unauthenticated viewers."
    )
    @GetMapping("/resolve")
    public ResponseEntity<ApiResponseDto<ResolvedLegalSourceDto>> resolveLegalSource(
            @RequestParam String iri) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        try {
            log.info("e-Sbírka resolve, iri: {}", iri);
            ResolvedLegalSourceDto dto = esbirkaService.resolveLegalSource(iri);
            return ResponseEntity.ok(ApiResponseDto.success(dto,
                    "Resolve finished"));
        } finally {
            MDC.remove(LOG_REQUEST_ID);
        }
    }

    private int resolveLimit(Integer limit) {
        int max = esbirkaConfig.getSearch().getMaxLimit();
        if (limit == null) return esbirkaConfig.getSearch().getDefaultLimit();
        if (limit < 1 || limit > max) {
            throw new IllegalArgumentException(
                    "Parametr limit musí být v rozsahu 1 až " + max + ".");
        }
        return limit;
    }

    private static void requireEsbirkaIri(String iri, String message) {
        if (!SparqlIriValidator.isEsbirkaEliIri(iri)) {
            throw new IllegalArgumentException(message);
        }
    }
}
