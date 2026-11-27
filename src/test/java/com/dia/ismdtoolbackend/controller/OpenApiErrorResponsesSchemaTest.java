package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.testsupport.context.FullContextTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The served OpenAPI document declares typed error responses (see {@code OpenApiErrorResponses}) and the paged
 * list shape. Same context as {@link OpenApiConflictSchemaTest}, so the two share one boot.
 */
@FullContextTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("junit")
@TestPropertySource(properties = {
        "full-context.db-name=openapi-conflict",
        "springdoc.api-docs.enabled=true",
        "esbirka.provider=rest",
        "esbirka.rest.api-key=test-key"
})
class OpenApiErrorResponsesSchemaTest {

    @Autowired private MockMvc mockMvc;

    private static JsonNode spec;

    @BeforeAll
    static void resetSpec() {
        spec = null;
    }

    private JsonNode spec() throws Exception {
        if (spec == null) {
            String body = mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
            spec = new ObjectMapper().readTree(body);
        }
        return spec;
    }

    @Test
    void ontologyEditDeclaresItsErrorResponsesWithTheErrorBody() throws Exception {
        JsonNode responses = spec().path("paths").path("/api/ontology/{ontologyId}/edit").path("patch")
                .path("responses");

        assertThat(responses.propertyNames()).contains("200", "400", "401", "403", "404", "409", "500");
        assertThat(responses.path("404").path("content").path("application/json").path("schema").path("$ref")
                .asText()).isEqualTo("#/components/schemas/ApiErrorResponse");
    }

    @Test
    void theErrorBodyTypesErrorCodeAsTheEnum() throws Exception {
        JsonNode schemas = spec().path("components").path("schemas");

        assertThat(schemas.path("ApiErrorResponse").path("properties").path("errorCode").path("$ref").asText())
                .isEqualTo("#/components/schemas/ErrorCode");
        assertThat(schemas.path("ErrorCode").path("enum").size()).isPositive();
    }

    @Test
    void anExplicitConflictBodyIsNotReplaced() throws Exception {
        JsonNode conflict = spec().path("paths").path("/api/diagram/{ontologySlug}/{diagramId}/materialize")
                .path("post").path("responses").path("409").path("content");

        assertThat(conflict.toString()).doesNotContain("ApiErrorResponse");
    }

    @Test
    void theOntologyListIsAPage() throws Exception {
        JsonNode list = spec().path("paths").path("/api/ontology/list").path("get");
        String ref = list.path("responses").path("200").path("content").path("*/*").path("schema").path("$ref")
                .asText(list.path("responses").path("200").path("content").toString());

        JsonNode wrapper = spec().path("components").path("schemas").path(ref.substring(ref.lastIndexOf('/') + 1));
        String pageRef = wrapper.path("properties").path("data").path("$ref").asText();
        JsonNode page = spec().path("components").path("schemas").path(pageRef.substring(pageRef.lastIndexOf('/') + 1));

        assertThat(page.path("properties").propertyNames()).contains("items", "totalCount", "limit", "offset");
        assertThat(list.path("parameters").toString()).contains("\"q\"", "\"sort\"", "\"graphName\"", "\"limit\"");
    }
}
