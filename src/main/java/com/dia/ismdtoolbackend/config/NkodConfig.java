package com.dia.ismdtoolbackend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * NKOD catalogue (dcat:Dataset) endpoint.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "nkod")
public class NkodConfig {

    private Sparql sparql = new Sparql();
    private Snapshot snapshot = new Snapshot();
    private Search search = new Search();

    @Data
    public static class Sparql {
        private String endpoint = "";
        private int timeout = 30000;
        private int maxConcurrentRequests = 4;
    }

    @Data
    public static class Snapshot {
        /** How long a harvested catalogue snapshot is served before it is considered stale. */
        private long ttlHours = 12;

        /** Background refresh cadence; fires inside {@code ttlHours} so no user pays the cold harvest. */
        private String refreshCron = "0 0 */6 * * *";

        /**
         * Upper bound on harvested rows.
         */
        private int maxRows = 100000;
    }

    @Data
    public static class Search {
        private int maxLimit = 100;
        private int defaultLimit = 20;
    }
}