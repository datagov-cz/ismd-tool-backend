package com.dia.ismdtoolbackend;

import com.dia.ismdtoolbackend.client.EsbirkaClient;
import com.dia.ismdtoolbackend.client.EsbirkaSparqlClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The application starts with {@code esbirka.provider=rest} and wires only the REST client. */
@SpringBootTest
@ActiveProfiles("junit")
@TestPropertySource(properties = {
        "esbirka.provider=rest",
        "esbirka.rest.api-key=test-key",
        // Own in-memory database: another context in this JVM may already hold testdb's schema.
        "spring.datasource.url=jdbc:h2:mem:esbirka-rest-provider;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "INIT=CREATE SCHEMA IF NOT EXISTS ismd_schema",
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
class EsbirkaRestProviderContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void wiresOnlyTheRestClient() {
        assertEquals("EsbirkaRestClient", context.getBean(EsbirkaClient.class).getClass().getSimpleName());
        assertEquals(0, context.getBeanNamesForType(EsbirkaSparqlClient.class).length);
        assertTrue(context.containsBean("esbirkaDeadlineFilter"));
    }
}
