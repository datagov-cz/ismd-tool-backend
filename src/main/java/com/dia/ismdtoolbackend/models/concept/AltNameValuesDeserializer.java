package com.dia.ismdtoolbackend.models.concept;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the {@code lang -> alt labels} map, accepting either shape per language key: a bare string or
 * an array of strings. Strings are wrapped into a single-element list, so callers always see lists.
 *
 * <p>Values must be JSON strings. A number, boolean, object, or nested array is rejected with a
 * {@link com.fasterxml.jackson.databind.exc.InvalidFormatException} rather than coerced or dropped —
 * silently discarding a malformed language would clear that language's stored labels, since the edit
 * path treats an absent key as "no alt names".
 *
 * <p>Blank values are dropped and duplicates within a language are collapsed: RDF stores
 * {@code skos:altLabel} as a set, so a repeated label cannot round-trip and would otherwise make every
 * subsequent edit re-detect a difference. Insertion order is preserved.
 */
public class AltNameValuesDeserializer extends JsonDeserializer<Map<String, List<String>>> {

    @Override
    public Map<String, List<String>> deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        JsonNode root = parser.getCodec().readTree(parser);
        if (root == null || root.isNull()) {
            return null;
        }
        if (!root.isObject()) {
            throw context.weirdStringException(root.toString(), Map.class,
                    "alternativní-název must be an object keyed by language tag");
        }

        Map<String, List<String>> byLanguage = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> property : root.properties()) {
            List<String> values = readValues(property.getKey(), property.getValue(), context);
            if (!values.isEmpty()) {
                byLanguage.put(property.getKey(), values);
            }
        }
        return byLanguage;
    }

    /** Reads one language's value: either an array of label strings or a single label string. */
    private List<String> readValues(String language, JsonNode node, DeserializationContext context)
            throws IOException {
        Set<String> unique = new LinkedHashSet<>();
        if (node == null || node.isNull()) {
            return new ArrayList<>(unique);
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                addLabel(unique, language, element, context);
            }
        } else {
            addLabel(unique, language, node, context);
        }
        return new ArrayList<>(unique);
    }

    private void addLabel(Set<String> values, String language, JsonNode node,
                          DeserializationContext context) throws IOException {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isTextual()) {
            throw context.weirdStringException(node.toString(), String.class,
                    "alternativní-název[" + language + "] must contain only strings");
        }
        String text = node.asText().trim();
        if (!text.isEmpty()) {
            values.add(text);
        }
    }
}