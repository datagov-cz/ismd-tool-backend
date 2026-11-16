package com.dia.ismdtoolbackend.testsupport.rdf;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Expands exported OFN JSON-LD to RDF offline. The export references the published context by
 * URL; this swaps in the bundled copy ({@value #CONTEXT_RESOURCE}) so the expansion is the real
 * one without network access.
 */
public final class JsonLd {

    public static final String CONTEXT_RESOURCE = "/com/dia/context/json_ld_context.jsonld";

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final JsonNode CONTEXT = loadContext();

    private JsonLd() {}

    /** Parses {@code exportedJson} with the bundled context in place of its {@code @context}. */
    public static Model expand(String exportedJson) {
        Model model = ModelFactory.createDefaultModel();
        RDFParser.fromString(withBundledContext(exportedJson), Lang.JSONLD).parse(model);
        return model;
    }

    /** {@code json} with its {@code @context} replaced by the bundled context, inline. */
    public static String withBundledContext(String json) {
        ObjectNode document = (ObjectNode) MAPPER.readTree(json);
        document.set("@context", CONTEXT);
        return MAPPER.writeValueAsString(document);
    }

    /** Terms the context defines at the top level (keywords excluded). */
    public static Set<String> rootTerms() {
        return terms(CONTEXT);
    }

    /** Terms the {@code pojmy}-scoped context defines, i.e. the keys a concept object may use. */
    public static Set<String> conceptTerms() {
        return terms(CONTEXT.get("pojmy").get("@context"));
    }

    private static Set<String> terms(JsonNode context) {
        Set<String> terms = new LinkedHashSet<>();
        context.propertyNames().forEach(name -> {
            if (!name.startsWith("@")) {
                terms.add(name);
            }
        });
        return terms;
    }

    private static JsonNode loadContext() {
        try (InputStream in = JsonLd.class.getResourceAsStream(CONTEXT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("bundled JSON-LD context missing: " + CONTEXT_RESOURCE);
            }
            return MAPPER.readTree(in).get("@context");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
