package com.dia.ismdtoolbackend.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Resolves the {@code production} profile with only the environment variables the deployment sets
 * (ismd-infrastructure {@code modules/tool_apps/backend.tf}), the way the container starts.
 */
class ProductionProfileEnvironmentTest {

    /** Env vars set on the backend container app; keep in step with {@code backend.tf}. */
    private static final Map<String, Object> DEPLOYMENT_ENV = Map.ofEntries(
            Map.entry("CORS_ALLOWED_ORIGINS", "https://ismd.example.gov.cz"),
            Map.entry("POSTGRES_URL", "jdbc:postgresql://pg.example.gov.cz:5432/ismd?sslmode=require"),
            Map.entry("POSTGRES_USER", "ismd"),
            Map.entry("POSTGRES_PASSWORD", "secret"),
            Map.entry("OUTBOX_DONE_RETENTION", "P7D"),
            Map.entry("FUSEKI_URL", "http://fuseki.example.internal:3030/ismd"),
            Map.entry("NKD_SPARQL_ENDPOINT", "https://nkd.example.gov.cz/sparql"),
            Map.entry("NKOD_SPARQL_ENDPOINT", "https://nkod.example.gov.cz/sparql"),
            Map.entry("VALIDATION_SERVICE_URL", "http://validator.example.internal/validujeme"),
            Map.entry("ISMD_AI_SERVICE_URL", "http://ai.example.internal:8080"),
            Map.entry("KEYCLOAK_ISSUER_URI", "https://auth.example.gov.cz/realms/ismd"),
            Map.entry("KEYCLOAK_CLIENT_ID", "ismd-backend"),
            Map.entry("KEYCLOAK_CLIENT_SECRET", "secret"),
            Map.entry("ESBIRKA_API_KEY", "key"));

    private static ConfigurableApplicationContext context;
    private static Environment env;

    @Configuration(proxyBeanMethods = false)
    static class NoBeans {
    }

    @BeforeAll
    static void start() {
        context = new SpringApplicationBuilder(NoBeans.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .profiles("production")
                .properties(DEPLOYMENT_ENV)
                .run();
        env = context.getEnvironment();
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    @Test
    void storesBindToTheDeploymentVariables() {
        assertThat(env.getProperty("spring.datasource.url")).isEqualTo(DEPLOYMENT_ENV.get("POSTGRES_URL"));
        assertThat(env.getProperty("spring.datasource.username")).isEqualTo(DEPLOYMENT_ENV.get("POSTGRES_USER"));
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo(DEPLOYMENT_ENV.get("POSTGRES_PASSWORD"));
        assertThat(env.getProperty("jena.fuseki.url")).isEqualTo(DEPLOYMENT_ENV.get("FUSEKI_URL"));
    }

    @Test
    void externalEndpointsAreConfigured() {
        assertThat(env.getProperty("nkd.sparql.endpoint")).isEqualTo(DEPLOYMENT_ENV.get("NKD_SPARQL_ENDPOINT"));
        assertThat(env.getProperty("nkod.sparql.endpoint")).isEqualTo(DEPLOYMENT_ENV.get("NKOD_SPARQL_ENDPOINT"));
        assertThat(env.getProperty("rpp.sparql.endpoint")).isNotBlank();
        assertThat(env.getProperty("esbirka.rest.api-key")).isEqualTo(DEPLOYMENT_ENV.get("ESBIRKA_API_KEY"));
    }

    @Test
    void apiDocumentationIsOff() {
        assertThat(env.getProperty("springdoc.api-docs.enabled", Boolean.class)).isFalse();
        assertThat(env.getProperty("springdoc.swagger-ui.enabled", Boolean.class)).isFalse();
    }

    @Test
    void everyKeyResolvesAndNoneFallsBackToLocalhost() throws IOException {
        TreeSet<String> keys = new TreeSet<>();
        keys.addAll(load("application.properties").stringPropertyNames());
        keys.addAll(load("application-production.properties").stringPropertyNames());

        for (String key : keys) {
            assertThatCode(() -> env.getProperty(key))
                    .as("%s resolves with the deployment variables only", key)
                    .doesNotThrowAnyException();
            assertThat(env.getProperty(key, ""))
                    .as(key)
                    .doesNotContain("localhost")
                    .doesNotContain("127.0.0.1");
        }
    }

    private static Properties load(String name) throws IOException {
        return PropertiesLoaderUtils.loadProperties(new ClassPathResource(name));
    }
}
