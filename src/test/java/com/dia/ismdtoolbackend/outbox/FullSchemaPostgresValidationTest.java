package com.dia.ismdtoolbackend.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Boots the FULL application context against REAL Postgres (Testcontainers) with
 * {@code ddl-auto=validate}, so every entity's mapping is checked against the actual Liquibase
 * schema on the real engine — not H2's lenient PG-compat mode.
 *
 * <p>This is the regression guard for {@code Instant}↔{@code timestamptz} and similar mismatches
 * that H2 silently tolerates: it caught {@code ValidationReportEntity.timestamp} (a bare
 * {@code Instant} on a {@code TIMESTAMP WITH TIME ZONE} column) failing strict validation. The
 * {@code @ServiceConnection} container datasource (from {@link PostgresIntegrationTestBase})
 * overrides the {@code junit} profile's H2 datasource while keeping its Fuseki/OAuth stubs.
 */
@SpringBootTest
@ActiveProfiles("junit")
@TestPropertySource(properties = {
        "spring.security.oauth2.client.registration.keycloak.client-id=test-client-id",
        "spring.security.oauth2.client.registration.keycloak.client-secret=test-client-secret",
        "spring.security.oauth2.client.registration.keycloak.provider=keycloak-test",
        "spring.security.oauth2.client.provider.keycloak-test.authorization-uri=http://localhost/auth",
        "spring.security.oauth2.client.provider.keycloak-test.token-uri=http://localhost/token",
        "spring.security.oauth2.client.provider.keycloak-test.user-info-uri=http://localhost/userinfo",
        "spring.security.oauth2.client.provider.keycloak-test.jwk-set-uri=http://localhost/jwks",
        "spring.security.oauth2.client.provider.keycloak-test.user-name-attribute=preferred_username",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/jwks"
})
class FullSchemaPostgresValidationTest extends PostgresIntegrationTestBase {

    @Test
    void contextLoadsAgainstRealPostgres() {
        // Success = Liquibase applied the whole changelog AND Hibernate ddl-auto=validate matched
        // every entity to the real Postgres schema. No assertion needed; a mismatch fails the boot.
    }
}
