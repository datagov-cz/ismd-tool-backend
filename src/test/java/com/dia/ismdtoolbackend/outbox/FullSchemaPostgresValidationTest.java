package com.dia.ismdtoolbackend.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoadsAgainstRealPostgres() {
        // Success = Liquibase applied the whole changelog AND Hibernate ddl-auto=validate matched
        // every entity to the real Postgres schema. No assertion needed; a mismatch fails the boot.
    }

    /**
     * The graph_name index changesets guard themselves with an {@code indexExists} precondition and
     * {@code MARK_RAN}, so a misspelled table or column would silently mark itself applied and create
     * nothing — booting is not proof the index landed. graph_name backs {@code findByGraphName} on
     * nearly every read path and, unlike slug/concept_iri, has no UNIQUE constraint to index it.
     */
    @Test
    void graphNameIndexesExist() {
        assertEquals(1, countIndex("ontologies", "idx_ontologies_graph_name"));
        assertEquals(1, countIndex("concepts", "idx_concepts_graph_name"));
    }

    private int countIndex(String table, String indexName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_indexes
                WHERE schemaname = 'ismd_schema' AND tablename = ? AND indexname = ?
                """, Integer.class, table, indexName);
        return count == null ? 0 : count;
    }
}
