package com.dia.ismdtoolbackend.publication;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the open-data publication of vocabularies and diagrams ({@code slovniky.jsonld},
 * {@code diagramy.jsonld}): built on demand by an admin and stored in PostgreSQL. See {@code docs/DATA_EXPORT.md}.
 *
 * <p>Mirrors {@code OutboxConfig} style ({@code @Data @Configuration @ConfigurationProperties}).
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ismd.publication")
public class PublicationConfig {

    /** Whether draft vocabularies, draft concepts and their diagram content are published. */
    private boolean includeDrafts = true;

    /** How many of the newest publications keep their file content in PostgreSQL; metadata is kept forever. */
    private int retainFiles = 30;

    /** How many vocabularies a run downloads at once; at least 1. The Fuseki semaphore still applies. */
    private int downloadParallelism = 4;

    public int getDownloadParallelism() {
        return Math.max(1, downloadParallelism);
    }

    /**
     * Base of the IRIs ISMD mints for publication: {@code <base>diagramy/<iri_id>} for diagrams and
     * {@code <base>publikace/<file>} for the publication records. Fixed once published.
     */
    private String iriBase = "https://data.dia.gov.cz/zdroj/ismd/";

    /**
     * URL of the ISMD OD JSON-LD context, the second entry of both files' {@code @context}. Served by GitHub
     * Pages from {@code pages/} in this repository; dated versions are immutable, {@code draft/} is not.
     */
    private String contextUrl = DRAFT_CONTEXT_URL;

    /** The working version of {@code pages/ismd-otevřená-data/draft/kontext.jsonld}. */
    public static final String DRAFT_CONTEXT_URL =
            "https://datagov-cz.github.io/ismd-tool-backend/ismd-otevřená-data/draft/kontext.jsonld";
}
