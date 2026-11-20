package com.dia.ismdtoolbackend.config;

import com.dia.ismdtoolbackend.config.security.SecurityConfig;
import com.dia.ismdtoolbackend.enums.ErrorCode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Declares the error responses every operation can return, with the {@code ApiResponseDto} error body and the
 * {@link ErrorCode} enum, so generated clients get typed errors. Which statuses an operation gets follows from
 * its path and method:
 * <ul>
 *   <li>all: 400, 500;</li>
 *   <li>outside {@link SecurityConfig#PUBLIC_PATHS}: 401, 403 (search: 401 only, for an invalid token);</li>
 *   <li>a path variable, or a detail/download read: 404;</li>
 *   <li>not GET: 409;</li>
 *   <li>backed by an external service (NKD, NKOD, e-Sbírka, RPP, AI, search): 502, 503.</li>
 * </ul>
 * A status the operation already declares (an explicit {@code @ApiResponse}) is left as it is.
 */
@Component
public class OpenApiErrorResponses implements OpenApiCustomizer {

    static final String ERROR_SCHEMA = "ApiErrorResponse";
    static final String ERROR_CODE_SCHEMA = "ErrorCode";

    private static final List<String> EXTERNAL_PREFIXES = List.of(
            "/api/nkd/", "/api/nkod/", "/api/codelist/nkod", "/api/eli/", "/api/rpp/", "/api/ai/", "/api/search");

    private final AntPathMatcher matcher = new AntPathMatcher();

    @Override
    public void customise(OpenAPI openApi) {
        Components components = openApi.getComponents() == null ? new Components() : openApi.getComponents();
        openApi.setComponents(components);
        components.addSchemas(ERROR_CODE_SCHEMA, errorCodeSchema());
        components.addSchemas(ERROR_SCHEMA, errorBodySchema());

        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().forEach((path, item) -> item.readOperationsMap()
                .forEach((method, operation) -> addErrors(path, method, operation)));
    }

    void addErrors(String path, PathItem.HttpMethod method, Operation operation) {
        Set<HttpStatus> statuses = new TreeSet<>(List.of(HttpStatus.BAD_REQUEST, HttpStatus.INTERNAL_SERVER_ERROR));
        String concretePath = path.replaceAll("\\{[^}]+}", "_");
        if (matchesAny(concretePath, SecurityConfig.SEARCH_PATHS)) {
            statuses.add(HttpStatus.UNAUTHORIZED);
        } else if (!matchesAny(concretePath, SecurityConfig.PUBLIC_PATHS)) {
            statuses.add(HttpStatus.UNAUTHORIZED);
            statuses.add(HttpStatus.FORBIDDEN);
        }
        if (path.contains("{") || path.endsWith("/detail") || path.endsWith("/download")) {
            statuses.add(HttpStatus.NOT_FOUND);
        }
        if (method != PathItem.HttpMethod.GET) {
            statuses.add(HttpStatus.CONFLICT);
        }
        if (EXTERNAL_PREFIXES.stream().anyMatch(path::startsWith)) {
            statuses.add(HttpStatus.BAD_GATEWAY);
            statuses.add(HttpStatus.SERVICE_UNAVAILABLE);
        }

        ApiResponses responses = operation.getResponses() == null ? new ApiResponses() : operation.getResponses();
        operation.setResponses(responses);
        for (HttpStatus status : statuses) {
            String code = String.valueOf(status.value());
            if (!responses.containsKey(code)) {
                responses.addApiResponse(code, errorResponse(status));
            }
        }
    }

    private boolean matchesAny(String path, String[] patterns) {
        return Arrays.stream(patterns).anyMatch(pattern -> matcher.match(pattern, path));
    }

    private static ApiResponse errorResponse(HttpStatus status) {
        String codes = Arrays.stream(ErrorCode.values())
                .filter(code -> code.getStatus().value() == status.value())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
        String description = status.getReasonPhrase() + (codes.isEmpty() ? "" : ". errorCode: " + codes);
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + ERROR_SCHEMA))));
    }

    private static Schema<?> errorCodeSchema() {
        StringSchema schema = new StringSchema();
        schema.setEnum(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
        schema.description("Strojově čitelný kód chyby; HTTP status plyne z kódu.");
        return schema;
    }

    private static Schema<?> errorBodySchema() {
        ObjectSchema schema = new ObjectSchema();
        schema.description("Tělo chybové odpovědi (ApiResponseDto bez dat, nebo s daty k chybě).");
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("success", new BooleanSchema().description("Vždy false."));
        properties.put("message", new StringSchema().description("Text chyby pro uživatele."));
        properties.put("errorCode", new Schema<>().$ref("#/components/schemas/" + ERROR_CODE_SCHEMA));
        properties.put("data", new ObjectSchema().nullable(true).description("Doplňující data k chybě, jinak null."));
        schema.setProperties(properties);
        schema.setRequired(List.of("success", "message", "errorCode"));
        return schema;
    }
}
