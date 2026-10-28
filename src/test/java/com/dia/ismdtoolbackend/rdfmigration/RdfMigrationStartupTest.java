package com.dia.ismdtoolbackend.rdfmigration;

import com.dia.ismdtoolbackend.IsmdToolBackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The runner as wired at startup: the real application with the full-context test settings, the
 * runner switched on, and Fuseki unreachable.
 */
class RdfMigrationStartupTest {

    private static ConfigurableApplicationContext start(String dbName, String location) {
        return new SpringApplicationBuilder(IsmdToolBackendApplication.class)
                .profiles("local")
                .run("--spring.config.import=classpath:testsupport/full-context.properties",
                        "--full-context.db-name=" + dbName,
                        "--server.port=0",
                        "--rdf-migration.enabled=true",
                        "--rdf-migration.location=" + location);
    }

    @Test
    void withNothingPendingTheApplicationStartsWithTheRunner() {
        try (ConfigurableApplicationContext context = start("rdf-startup-ok", "classpath*:no-rdf-migrations-here")) {
            assertThat(context.getBeansOfType(RdfMigrationRunner.class)).hasSize(1);
        }
    }

    @Test
    void aPendingChangesetThatCannotRunStopsStartup() {
        Throwable failure = catchThrowable(() ->
                start("rdf-startup-fail", "classpath*:testsupport/rdf-migrations-startup").close());

        assertThat(failure).isNotNull();
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        assertThat(trace.toString())
                .contains("RdfMigrationRunner.afterSingletonsInstantiated")
                .contains("Failed to list named graphs");
    }
}
