package com.dia.ismdtoolbackend.config;

import com.dia.ismdtoolbackend.enums.ErrorCode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiErrorResponsesTest {

    private final OpenApiErrorResponses customizer = new OpenApiErrorResponses();

    private static Operation ok() {
        return new Operation().responses(new ApiResponses().addApiResponse("200", new ApiResponse().description("OK")));
    }

    private OpenAPI customised(String path, PathItem item) {
        OpenAPI openApi = new OpenAPI().paths(new Paths().addPathItem(path, item));
        customizer.customise(openApi);
        return openApi;
    }

    @Test
    void securedWriteWithAPathVariableListsEveryApplicableStatus() {
        Operation edit = ok();
        customised("/api/ontology/{ontologyId}/edit", new PathItem().patch(edit));

        assertThat(edit.getResponses()).containsOnlyKeys("200", "400", "401", "403", "404", "409", "500");
        assertThat(edit.getResponses().get("404").getContent().get("application/json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/ApiErrorResponse");
        assertThat(edit.getResponses().get("404").getDescription())
                .contains("ONTOLOGY_NOT_FOUND", "CONCEPT_NOT_FOUND")
                .doesNotContain("RDF_DATA_MISSING");
        assertThat(edit.getResponses().get("500").getDescription()).contains("RDF_DATA_MISSING");
    }

    @Test
    void publicReadHasNoAuthStatuses() {
        Operation list = ok();
        customised("/api/ontology/list", new PathItem().get(list));

        assertThat(list.getResponses()).containsOnlyKeys("200", "400", "500");
    }

    @Test
    void externalBackedPublicDetailGetsNotFoundAndUpstreamStatuses() {
        Operation detail = ok();
        customised("/api/nkd/ontology/detail", new PathItem().get(detail));

        assertThat(detail.getResponses()).containsOnlyKeys("200", "400", "404", "500", "502", "503");
    }

    @Test
    void searchAcceptsAnonymousButRejectsABadToken() {
        Operation search = ok();
        customised("/api/search", new PathItem().get(search));

        assertThat(search.getResponses()).containsKey("401").doesNotContainKey("403");
    }

    @Test
    void anExplicitlyDeclaredStatusIsKept() {
        ApiResponse conflict = new ApiResponse().description("Diagram mezitím uložil někdo jiný.");
        Operation rename = ok();
        rename.getResponses().addApiResponse("409", conflict);
        customised("/api/diagram/{ontologySlug}/{diagramId}/rename", new PathItem().patch(rename));

        assertThat(rename.getResponses().get("409")).isSameAs(conflict);
        assertThat(rename.getResponses()).containsKeys("401", "403", "404");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void registersTheErrorBodyAndEveryErrorCode() {
        OpenAPI openApi = customised("/api/ontology/list", new PathItem().get(ok()));

        Schema codes = openApi.getComponents().getSchemas().get("ErrorCode");
        assertThat(codes.getEnum()).containsExactlyElementsOf(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
        Schema body = openApi.getComponents().getSchemas().get("ApiErrorResponse");
        assertThat(body.getRequired()).containsExactlyInAnyOrder("success", "message", "errorCode");
        assertThat(((Schema) body.getProperties().get("errorCode")).get$ref()).isEqualTo("#/components/schemas/ErrorCode");
    }
}
