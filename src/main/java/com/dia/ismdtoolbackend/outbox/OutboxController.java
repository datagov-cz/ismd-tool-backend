package com.dia.ismdtoolbackend.outbox;

import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.outbox.dto.OutboxEntryDto;
import com.dia.ismdtoolbackend.outbox.dto.OutboxStatusDto;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin-only observability + recovery for the PG↔TDB2 outbox.
 *
 * <p>The path {@code /api/admin/outbox/**} must be registered in {@code SecurityConfig}'s
 * authenticated chain or it 403s at {@code denyAll()} before {@code @PreAuthorize} runs (mirrors the
 * reconciler controller).
 */
@RestController
@RequestMapping("/api/admin/outbox")
@RequiredArgsConstructor
@Slf4j
public class OutboxController {

    private final OutboxAdminService adminService;

    @Operation(summary = "Outbox queue health: counts by status + oldest pending row age.")
    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponseDto<OutboxStatusDto>> status() {
        return ResponseEntity.ok(ApiResponseDto.success(adminService.status(), "Outbox status"));
    }

    @Operation(summary = "List FAILED outbox rows (metadata + error; no triple payloads).")
    @GetMapping("/failed")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponseDto<List<OutboxEntryDto>>> failed() {
        return ResponseEntity.ok(ApiResponseDto.success(adminService.failed(), "Failed outbox rows"));
    }

    @Operation(summary = "Force an outbox drain pass on demand.")
    @PostMapping("/drain")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponseDto<Integer>> drain() {
        int applied = adminService.drainNow();
        return ResponseEntity.ok(ApiResponseDto.success(applied, "Drained; rows applied: " + applied));
    }

    @Operation(summary = "Retry a FAILED outbox row (reset to PENDING). Only FAILED rows are retryable.")
    @PostMapping("/retry/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponseDto<Void>> retry(@PathVariable Long id) {
        boolean reset = adminService.retry(id);
        if (!reset) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponseDto.error(
                    "Row " + id + " not found or not in FAILED state; nothing to retry."));
        }
        return ResponseEntity.ok(ApiResponseDto.success("Row " + id + " reset to PENDING for retry"));
    }
}
