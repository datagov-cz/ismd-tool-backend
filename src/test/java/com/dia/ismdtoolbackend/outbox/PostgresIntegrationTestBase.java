package com.dia.ismdtoolbackend.outbox;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for JPA/Liquibase integration tests that need REAL Postgres semantics the H2 PG-compat
 * profile can't honour — {@code FOR UPDATE SKIP LOCKED}, sequences, {@code TIMESTAMP WITH TIME
 * ZONE}. A single container is shared across all subclasses. Spring's {@code @ServiceConnection}
 * wires the datasource; Liquibase then applies the real changelog and Hibernate
 * {@code ddl-auto=validate} confirms the entities match.
 */
public abstract class PostgresIntegrationTestBase {

    // Manual singleton container: started once in the static initializer and never stopped, so it
    // survives across every subclass in the shared Surefire fork (reuseForks=true, one JVM). Do NOT
    // use @Container/@Testcontainers — that hands lifecycle to the extension, which stops the
    // container in afterAll of the FIRST subclass, leaving every later subclass with a dead
    // container (connection refused). Ryuk terminates it at JVM exit.
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    // Raise the 100-slot default: each subclass is a separate Spring context with
                    // its own pool, and all of them stay cached for the JVM's lifetime.
                    .withCommand("postgres", "-c", "max_connections=300")
                    // Create the app schema as a DB init script — runs once at container start,
                    // before Liquibase connects. (Hikari connection-init-sql is not reliably
                    // applied to the Liquibase connection under @ServiceConnection.)
                    .withInitScript("testcontainers/init-schema.sql");

    static {
        POSTGRES.start();
    }

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
