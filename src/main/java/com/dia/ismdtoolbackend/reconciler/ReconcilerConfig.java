package com.dia.ismdtoolbackend.reconciler;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the PG↔TDB2 consistency reconciler.
 *
 * <p><b>Detection-only phase.</b> Only {@link #enabled} (the scheduled-job master switch) and
 * {@link #cron} are live. The reconciler never mutates either store yet — auto-repair, the
 * quarantine window, and the orphan-graph delete are deliberately absent until the
 * repair-safety machinery (quarantine table, pre-delete audit, distributed lock) lands. The
 * manual admin endpoint runs detection on demand regardless of {@link #enabled}.
 *
 * <p>There is intentionally NO {@code published-only} flag: draft concepts
 * ({@code isPublished=false}) carry full owned RDF + a PG row and are in scope identically to
 * published; filtering them out would wrongly suppress them.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "reconciler")
public class ReconcilerConfig {

    /** Master switch for the SCHEDULED run. Off by default; the admin endpoint works regardless. */
    private boolean enabled = false;

    /** Cron expression for the scheduled run (Spring 6-field). Default 03:00 daily. */
    private String cron = "0 0 3 * * *";
}