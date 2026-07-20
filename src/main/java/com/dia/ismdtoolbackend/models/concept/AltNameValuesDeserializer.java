package com.dia.ismdtoolbackend.models.concept;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the {@code lang -> alt labels} map, accepting either shape per language key:
 * a bare string or an array. Strings are wrapped into a single-element list, so
 * callers always see lists.
 *
 * <p>Null and blank entries are dropped; a language whose values are all blank is omitted entirely.
 * Insertion order is preserved so round-tripping a payload keeps its ordering.
 */
public class AltNameValuesDeserializer extends JsonDeserializer<Map<String, List<String>>> {

    @Override
    public Map<String, List<String>> deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        JsonNode root = parser.getCodec().readTree(parser);
        if (root == null || root.isNull()) {
            return null;
        }

        Map<String, List<String>> byLanguage = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> property : root.properties()) {
            List<String> values = readValues(property.getValue());
            if (!values.isEmpty()) {
                byLanguage.put(property.getKey(), values);
            }
        }
        return byLanguage;
    }

    /** Reads one language's value, which is either an array of labels or a single label. */
    private List<String> readValues(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                addIfNotBlank(values, element);
            }
        } else {
            addIfNotBlank(values, node);
        }
        return values;
    }

    private void addIfNotBlank(List<String> values, JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        String text = node.asText();
        if (text != null && !text.trim().isEmpty()) {
            values.add(text.trim());
        }
    }
}