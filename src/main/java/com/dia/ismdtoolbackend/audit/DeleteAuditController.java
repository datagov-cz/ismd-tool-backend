package com.dia.ismdtoolbackend.audit;

import com.dia.ismdtoolbackend.audit.dto.DeleteAuditEntryDto;
import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.controller.dto.PageDto;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Admin read of the delete audit trail.
 *
 * <p>The path {@code /api/admin/audit/**} must be registered in {@code SecurityConfig}'s authenticated chain or
 * it 403s at {@code denyAll()} before {@code @PreAuthorize} runs.
 */
@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
public class DeleteAuditController {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final DeleteAuditRepository repository;

    @Operation(summary = "Záznamy o smazání",
            description = "Smazané slovníky, pojmy, lokální kopie, komentáře a diagramy, od nejnovějších. "
                    + "Filtry jsou volitelné; from je včetně, to bez. Pouze pro administrátora.")
    @GetMapping("/deletes")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponseDto<PageDto<DeleteAuditEntryDto>>> deletes(
            @RequestParam(required = false) DeletedEntityType type,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(defaultValue = "0") int offset) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit musí být mezi 1 a " + MAX_LIMIT + ".");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset nesmí být záporný.");
        }
        String typeName = type == null ? null : type.name();
        List<DeleteAuditEntryDto> items = repository.findPage(typeName, actorId, from, to, limit, offset).stream()
                .map(DeleteAuditEntryDto::of)
                .toList();
        long total = repository.countMatching(typeName, actorId, from, to);
        return ResponseEntity.ok(ApiResponseDto.success(new PageDto<>(items, total, limit, offset),
                "Záznamy o smazání"));
    }
}
