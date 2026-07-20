package com.dia.ismdtoolbackend.outbox;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for JPA/Liquibase integration tests that need REAL Postgres semantics the H2 PG-compat
 * profile can't honour — {@code FOR UPDATE SKIP LOCKED}, sequences, {@code TIMESTAMP WITH TIME
 * ZONE}. A single container is shared across all subclasses (static + reused). Spring's
 * {@code @ServiceConnection} wires the datasource; Liquibase then applies the real changelog and
 * Hibernate {@code ddl-auto=validate} confirms the entities match.
 */
@Testcontainers
public abstract class PostgresIntegrationTestBase {

    // Shared static container managed by the @Testcontainers extension (started before the class,
    // stopped after) — intentionally NOT try-with-resources: that would close it after first use.
    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    // Create the app schema as a DB init script — runs once at container start,
                    // before Liquibase connects. (Hikari connection-init-sql is not reliably
                    // applied to the Liquibase connection under @ServiceConnection.)
                    .withInitScript("testcontainers/init-schema.sql");

    @DynamicPropertySource
    static void schemaProps(DynamicPropertyRegistry registry) {
        // Pin the schema everywhere, mirroring the real profiles. The schema itself is created by
        // the init script above.
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "ismd_schema");
        registry.add("spring.liquibase.default-schema", () -> "ismd_schema");
        registry.add("spring.liquibase.liquibase-schema", () -> "ismd_schema");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
}
