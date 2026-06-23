package com.dia.ismdtoolbackend.reconciler;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.reconciler.dto.ReconciliationReportDto;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only operations for the PG↔TDB2 consistency reconciler (detection-only phase).
 *
 * <p>The endpoint path {@code /api/admin/reconciler/**} must be registered in
 * {@code SecurityConfig}'s authenticated chain, or it 403s at {@code denyAll()} before
 * {@code @PreAuthorize} runs.
 */
@RestController
@RequestMapping("/api/admin/reconciler")
@RequiredArgsConstructor
@Slf4j
public class ReconcilerController {

    private final ReconcilerRunService runService;

    @Operation(summary = "Run a PG↔TDB2 consistency scan on demand (detection-only; no repair).")
    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponseDto<ReconciliationReportDto>> run(
            @AuthenticationPrincipal SecurityUser user) {
        String triggeredBy = "ADMIN:" + (user != null ? user.getUsername() : "unknown");
        ReconciliationReport report = runService.run(triggeredBy);
        if (report == null) {
            // A scheduled or another manual run holds the guard. Do NOT return a stale/empty
            // report (reads as "all clear") — signal the conflict and point at GET /report.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponseDto.error(
                    "A reconciliation run is already in progress. Try GET /api/admin/reconciler/report."));
        }
        return ResponseEntity.ok(ApiResponseDto.success(
                ReconciliationReportDto.from(report), "Reconciliation scan complete"));
    }

    @Operation(summary = "Get the most recent PG↔TDB2 reconciliation report.")
    @GetMapping("/report")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponseDto<ReconciliationReportDto>> lastReport() {
        ReconciliationReport report = runService.getLastReport();
        if (report == null) {
            return ResponseEntity.ok(ApiResponseDto.success(
                    null, "No reconciliation has run since startup"));
        }
        return ResponseEntity.ok(ApiResponseDto.success(
                ReconciliationReportDto.from(report), "Latest reconciliation report"));
    }
}