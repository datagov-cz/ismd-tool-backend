package com.dia.ismdtoolbackend.ofn;

import com.dia.ismdtoolbackend.ofn.OfnContextIndex.Term;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Format;
import com.networknt.schema.format.IriFormat;
import com.networknt.schema.regex.RegularExpression;
import com.networknt.schema.regex.RegularExpressionFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What the schema allows as a text value in each field the context types {@code @id}: {@code format: iri}
 * checked by the schema validator's own format, a {@code pattern} by its own regex engine, and no text at all
 * where the schema expects an object. A value that passes here never fails the schema for its shape.
 * Built once from the schema; a field the schema defines with two different rules fails startup.
 */
public final class OfnIdValueRules {

    private static final Format IRI = new IriFormat();
    private static final String ARRAY = "array";
    private static final String OBJECT = "object";

    /** A field's rule: an object, or a text with an IRI format and/or a pattern. */
    private record Rule(boolean object, boolean iri, String pattern, RegularExpression regex) {

        boolean sameAs(Rule other) {
            return object == other.object && iri == other.iri && Objects.equals(pattern, other.pattern);
        }
    }

    /** Fields the schema does not define: the context's {@code @id} still needs an IRI. */
    private static final Rule ANY_IRI = new Rule(false, true, null, null);

    private final Map<String, Rule> rules = new HashMap<>();

    OfnIdValueRules(OfnContextIndex context, JsonNode schema, RegularExpressionFactory regexFactory) {
        Set<String> idTerms = new HashSet<>();
        for (Term term : context.allTerms().values()) {
            if ("@id".equals(term.type())) {
                idTerms.add(term.name());
            }
        }
        idTerms.add(context.idAlias());
        collect(schema, idTerms, regexFactory);
    }

    /** Why the text does not fit the field, or null when it fits. */
    public String problem(String field, String text) {
        Rule rule = rules.getOrDefault(field, ANY_IRI);
        if (rule.object()) {
            return "pole očekává objekt";
        }
        if (rule.iri() && !IRI.matches(null, text)) {
            return "hodnota není IRI";
        }
        if (rule.regex() != null && !rule.regex().matches(text)) {
            return "hodnota neodpovídá vzoru " + rule.pattern();
        }
        return null;
    }

    private void collect(JsonNode node, Set<String> idTerms, RegularExpressionFactory regexFactory) {
        if (node.isArray()) {
            node.forEach(child -> collect(child, idTerms, regexFactory));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        node.path("properties").properties().forEach(entry -> {
            if (idTerms.contains(entry.getKey())) {
                add(entry.getKey(), entry.getValue(), regexFactory);
            }
        });
        node.forEach(child -> collect(child, idTerms, regexFactory));
    }

    private void add(String field, JsonNode definition, RegularExpressionFactory regexFactory) {
        JsonNode item = ARRAY.equals(definition.path("type").asText()) ? definition.path("items") : definition;
        for (String unsupported : new String[]{"$ref", "anyOf", "oneOf", "allOf", "enum", "const"}) {
            if (item.has(unsupported)) {
                throw new IllegalStateException("OFN schema: field " + field + " uses " + unsupported
                        + ", which the @id value check does not read");
            }
        }
        String pattern = item.has("pattern") ? item.path("pattern").asText() : null;
        Rule rule = new Rule(OBJECT.equals(item.path("type").asText()), "iri".equals(item.path("format").asText()),
                pattern, pattern == null ? null : regexFactory.getRegularExpression(pattern));
        Rule previous = rules.putIfAbsent(field, rule);
        if (previous != null && !previous.sameAs(rule)) {
            throw new IllegalStateException("OFN schema: field " + field + " has two different value rules");
        }
    }
}
