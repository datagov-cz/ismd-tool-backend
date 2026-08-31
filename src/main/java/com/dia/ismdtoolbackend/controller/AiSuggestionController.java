package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.controller.dto.ai.AiClassSuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiClassSuggestionRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiFeedbackRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiJobStartResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiPropertySuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiRelationshipSuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiSelectedClassSuggestionRequestDto;
import com.dia.ismdtoolbackend.service.AiSuggestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiSuggestionController {

    private final AiSuggestionService aiSuggestionService;

    @Operation(
            summary = "Spustí generování návrhů tříd z právního aktu",
            description = """
                    Vytvoří asynchronní úlohu pro návrh nových tříd slovníku. Klient v těle požadavku
                    předá vybrané části právního aktu, volitelný textový kontext a volitelné slugy
                    slovníků známého konceptuálního modelu. Backend podle slugů sestaví společný známý
                    konceptuální model, doplní počet návrhů z konfigurace a požadavek předá službě
                    ISMD AI. Vyžaduje autentizaci.
                    """
    )
    @PostMapping("/legal-acts/{year}/{number}/{date}/class-suggestions")
    public ResponseEntity<AiJobStartResponseDto> startClassSuggestions(
            @PathVariable int year,
            @PathVariable int number,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody AiClassSuggestionRequestDto request,
            @Parameter(hidden = true)
            @CurrentSecurityContext(expression = "authentication.credentials", errorOnInvalidType = true) Jwt jwt
    ) {
        return ResponseEntity.accepted()
                .body(aiSuggestionService.startClassSuggestions(
                        jwt.getTokenValue(),
                        year,
                        number,
                        date,
                        request
                ));
    }

    @Operation(
            summary = "Spustí generování návrhů atributů vybrané třídy z právního aktu",
            description = """
                    Vytvoří asynchronní úlohu pro návrh nových atributů vybrané třídy. Klient v těle
                    požadavku předá identifikátor vybrané třídy, vybrané části právního aktu, volitelný
                    textový kontext a volitelné slugy slovníků známého konceptuálního modelu. Backend
                    podle slugů sestaví společný známý konceptuální model, doplní počet návrhů
                    z konfigurace a požadavek předá službě ISMD AI. Vyžaduje autentizaci.
                    """
    )
    @PostMapping("/legal-acts/{year}/{number}/{date}/property-suggestions")
    public ResponseEntity<AiJobStartResponseDto> startPropertySuggestions(
            @PathVariable int year,
            @PathVariable int number,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody AiSelectedClassSuggestionRequestDto request,
            @Parameter(hidden = true)
            @CurrentSecurityContext(expression = "authentication.credentials", errorOnInvalidType = true) Jwt jwt
    ) {
        return ResponseEntity.accepted()
                .body(aiSuggestionService.startPropertySuggestions(
                        jwt.getTokenValue(),
                        year,
                        number,
                        date,
                        request
                ));
    }

    @Operation(
            summary = "Spustí generování návrhů vztahů vybrané třídy z právního aktu",
            description = """
                    Vytvoří asynchronní úlohu pro návrh nových vztahů vybrané zdrojové třídy. Klient
                    v těle požadavku předá identifikátor vybrané třídy, vybrané části právního aktu,
                    volitelný textový kontext a volitelné slugy slovníků známého konceptuálního modelu.
                    Backend podle slugů sestaví společný známý konceptuální model, doplní počet návrhů
                    z konfigurace a požadavek předá službě ISMD AI. Vyžaduje autentizaci.
                    """
    )
    @PostMapping("/legal-acts/{year}/{number}/{date}/relationship-suggestions")
    public ResponseEntity<AiJobStartResponseDto> startRelationshipSuggestions(
            @PathVariable int year,
            @PathVariable int number,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody AiSelectedClassSuggestionRequestDto request,
            @Parameter(hidden = true)
            @CurrentSecurityContext(expression = "authentication.credentials", errorOnInvalidType = true) Jwt jwt
    ) {
        return ResponseEntity.accepted()
                .body(aiSuggestionService.startRelationshipSuggestions(
                        jwt.getTokenValue(),
                        year,
                        number,
                        date,
                        request
                ));
    }

    @Operation(
            summary = "Vrátí stav a výsledky úloh pro návrhy tříd",
            description = """
                    Vrátí aktuální stav a případné návrhy tříd pro všechny úlohy zadané opakovaným
                    query parametrem jobIds. Vyžaduje autentizaci.
                    """
    )
    @GetMapping("/legal-acts/class-suggestions-jobs")
    public ResponseEntity<List<AiClassSuggestionsJobResponseDto>> getClassSuggestions(
            @RequestParam("jobIds") @NotEmpty List<UUID> jobIds,
            @Parameter(hidden = true)
            @CurrentSecurityContext(expression = "authentication.credentials", errorOnInvalidType = true) Jwt jwt
    ) {
        return ResponseEntity.ok(aiSuggestionService.getClassSuggestions(jwt.getTokenValue(), jobIds));
    }

    @Operation(
            summary = "Vrátí stav a výsledky úloh pro návrhy atributů",
            description = """
                    Vrátí aktuální stav a případné návrhy atributů pro všechny úlohy zadané opakovaným
                    query parametrem jobIds. Výsledek každé úlohy obsahuje také identifikátor vybrané třídy.
                    Vyžaduje autentizaci.
                    """
    )
    @GetMapping("/legal-acts/property-suggestions-jobs")
    public ResponseEntity<List<AiPropertySuggestionsJobResponseDto>> getPropertySuggestions(
            @RequestParam("jobIds") @NotEmpty List<UUID> jobIds,
            @Parameter(hidden = true)
            @CurrentSecurityContext(expression = "authentication.credentials", errorOnInvalidType = true) Jwt jwt
    ) {
        return ResponseEntity.ok(aiSuggestionService.getPropertySuggestions(jwt.getTokenValue(), jobIds));
    }

    @Operation(
            summary = "Vrátí stav a výsledky úloh pro návrhy vztahů",
            description = """
                    Vrátí aktuální stav a případné návrhy vztahů pro všechny úlohy zadané opakovaným
                    query parametrem jobIds. Výsledek každé úlohy obsahuje také identifikátor zdrojové třídy.
                    Vyžaduje autentizaci.
                    """
    )
    @GetMapping("/legal-acts/relationship-suggestions-jobs")
    public ResponseEntity<List<AiRelationshipSuggestionsJobResponseDto>> getRelationshipSuggestions(
            @RequestParam("jobIds") @NotEmpty List<UUID> jobIds,
            @Parameter(hidden = true)
            @CurrentSecurityContext(expression = "authentication.credentials", errorOnInvalidType = true) Jwt jwt
    ) {
        return ResponseEntity.ok(aiSuggestionService.getRelationshipSuggestions(jwt.getTokenValue(), jobIds));
    }

    @Operation(
            summary = "Označí návrhy jako přijaté",
            description = """
                    Uloží informaci, že uživatel vybrané návrhy z uvedených úloh přijal do
                    konceptuálního modelu. Vyžaduje autentizaci.
                    """
    )
    @PostMapping("/accept-suggestion")
    public ResponseEntity<Void> acceptSuggestions(
            @RequestBody List<@NotNull @Valid AiFeedbackRequestDto> requests,
            @Parameter(hidden = true)
            @CurrentSecurityContext(expression = "authentication.credentials", errorOnInvalidType = true) Jwt jwt
    ) {
        aiSuggestionService.acceptSuggestions(jwt.getTokenValue(), requests);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Označí návrhy jako užitečné",
            description = """
                    Uloží pozitivní zpětnou vazbu uživatele k vybraným návrhům z uvedených úloh,
                    aniž by tím vyjadřoval jejich přijetí do modelu. Vyžaduje autentizaci.
                    """
    )
    @PostMapping("/like-suggestion")
    public ResponseEntity<Void> likeSuggestions(
            @RequestBody List<@NotNull @Valid AiFeedbackRequestDto> requests,
            @Parameter(hidden = true)
            @CurrentSecurityContext(expression = "authentication.credentials", errorOnInvalidType = true) Jwt jwt
    ) {
        aiSuggestionService.likeSuggestions(jwt.getTokenValue(), requests);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Označí návrhy jako neužitečné",
            description = """
                    Uloží negativní zpětnou vazbu uživatele k vybraným návrhům z uvedených úloh.
                    Vyžaduje autentizaci.
                    """
    )
    @PostMapping("/dislike-suggestion")
    public ResponseEntity<Void> dislikeSuggestions(
            @RequestBody List<@NotNull @Valid AiFeedbackRequestDto> requests,
            @Parameter(hidden = true)
            @CurrentSecurityContext(expression = "authentication.credentials", errorOnInvalidType = true) Jwt jwt
    ) {
        aiSuggestionService.dislikeSuggestions(jwt.getTokenValue(), requests);
        return ResponseEntity.noContent().build();
    }
}
