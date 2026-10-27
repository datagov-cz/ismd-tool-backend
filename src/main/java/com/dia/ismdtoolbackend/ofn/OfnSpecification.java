package com.dia.ismdtoolbackend.ofn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The OFN slovníky specification the converter conforms to: the JSON-LD context and the JSON Schema
 * bundled in {@value #CONTEXT_RESOURCE} and {@value #SCHEMA_RESOURCE}.
 * <p>
 * These two files are the single source of truth. A new OFN version is adopted by replacing them;
 * the context URL written to the output is read from the schema, which pins it.
 */
public final class OfnSpecification {

    public static final String CONTEXT_RESOURCE = "ofn/slovniky/kontext.jsonld";
    public static final String SCHEMA_RESOURCE = "ofn/slovniky/schema.json";

    /** Key of the concept list in a vocabulary document; the schema and context both use it. */
    public static final String CONCEPTS_KEY = "pojmy";

    private static volatile OfnSpecification bundled;

    private final JsonNode contextDocument;
    private final OfnContextIndex contextIndex;
    private final JsonNode schemaDocument;
    private final JsonSchema schema;
    private final JsonSchema conceptSchema;
    private final Map<String, JsonSchema> conceptSchemasByType;
    private final String contextUrl;

    public OfnSpecification(JsonNode contextDocument, JsonNode schemaDocument) {
        this.contextDocument = contextDocument;
        this.contextIndex = new OfnContextIndex(contextDocument);
        this.schemaDocument = schemaDocument;

        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        this.schema = factory.getSchema(schemaDocument, config);

        this.conceptSchema = factory.getSchema(
                schemaDocument.path("properties").path(CONCEPTS_KEY).path("items"), config);
        Map<String, JsonSchema> variants = new LinkedHashMap<>();
        for (JsonNode variant : conceptVariants()) {
            variants.put(variant.path("title").asText(), factory.getSchema(variant, config));
        }
        this.conceptSchemasByType = Collections.unmodifiableMap(variants);
        this.contextUrl = readContextUrl(schemaDocument);
    }

    /** The specification bundled with the application (loaded once). */
    public static OfnSpecification bundled() {
        OfnSpecification result = bundled;
        if (result == null) {
            synchronized (OfnSpecification.class) {
                result = bundled;
                if (result == null) {
                    ObjectMapper mapper = new ObjectMapper();
                    result = new OfnSpecification(readResource(mapper, CONTEXT_RESOURCE),
                            readResource(mapper, SCHEMA_RESOURCE));
                    bundled = result;
                }
            }
        }
        return result;
    }

    /** The context document, e.g. for resolving the context URL offline. */
    public JsonNode contextDocument() {
        return contextDocument;
    }

    public OfnContextIndex contextIndex() {
        return contextIndex;
    }

    public JsonNode schemaDocument() {
        return schemaDocument;
    }

    /** The whole-document schema. */
    public JsonSchema schema() {
        return schema;
    }

    /** The schema of one item of {@code pojmy} (any concept variant). */
    public JsonSchema conceptSchema() {
        return conceptSchema;
    }

    /**
     * Concept variants of the schema ({@code pojmy.items.anyOf}) by their title, e.g. "Třída".
     * The schema's {@code anyOf} is open (every concept passes as "Pojem"); its intent is that a
     * concept of each type satisfies that type's variant.
     */
    public Map<String, JsonSchema> conceptSchemasByType() {
        return conceptSchemasByType;
    }

    /** The {@code @context} value the schema requires in a document. */
    public String contextUrl() {
        return contextUrl;
    }

    private Iterable<JsonNode> conceptVariants() {
        return schemaDocument.path("properties").path(CONCEPTS_KEY).path("items").path("anyOf");
    }

    /** The schema pins {@code @context} as a const (or as a const an array must contain). */
    private static String readContextUrl(JsonNode schemaDocument) {
        JsonNode contextSchema = schemaDocument.path("properties").path("@context");
        for (JsonNode option : contextSchema.path("oneOf")) {
            if (option.has("const")) {
                return option.get("const").asText();
            }
        }
        if (contextSchema.has("const")) {
            return contextSchema.get("const").asText();
        }
        throw new IllegalStateException("OFN schema does not pin the @context URL");
    }

    private static JsonNode readResource(ObjectMapper mapper, String path) {
        try (InputStream in = OfnSpecification.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing bundled OFN resource: " + path);
            }
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read bundled OFN resource: " + path, e);
        }
    }
}
