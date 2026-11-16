package com.dia.ismdtoolbackend.publication;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Configuration for the open-data publication of vocabularies and diagrams ({@code slovniky.jsonld},
 * {@code diagramy.jsonld}). See {@code .planning/Publikace_OD (1).md}.
 *
 * <p>Mirrors {@code OutboxConfig} style ({@code @Data @Configuration @ConfigurationProperties}).
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ismd.publication")
public class PublicationConfig {

    /** Master switch for the scheduler and the public endpoints. Off until the ISMD OD context URL resolves. */
    private boolean enabled = false;

    /** Whether draft vocabularies, draft concepts and their diagram content are published. */
    private boolean includeDrafts = true;

    /** Maximum age of the current publication before the next check rebuilds it. */
    private Duration interval = Duration.ofDays(30);

    /** Cron (Spring 6-field) on which the scheduler compares the publication's age with {@link #interval}. */
    private String checkCron = "0 0 3 * * *";

    /** Directory holding the published files. */
    private Path directory = Path.of(System.getProperty("java.io.tmpdir"), "ismd-publication");

    /**
     * Base of the IRIs ISMD mints for publication: {@code <base>diagramy/<iri_id>} for diagrams and
     * {@code <base>publikace/<file>} for the publication records. Fixed once published.
     */
    private String iriBase = "https://data.dia.gov.cz/zdroj/ismd/";

    /** URL of the ISMD OD JSON-LD context, the second entry of both files' {@code @context}. */
    private String contextUrl;
}
