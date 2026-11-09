package com.dia.ismdtoolbackend.ofn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.regex.JDKRegularExpressionFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rule table is read from the schema and refuses a schema it cannot mirror exactly. */
class OfnIdValueRulesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OfnContextIndex CONTEXT = new OfnContextIndex(json("""
            {"@context": {"iri": "@id", "typ": "@type", "ex": "https://example.org/",
              "odkaz": {"@id": "ex:odkaz", "@type": "@id"},
              "kód": {"@id": "ex:kód", "@type": "@id"}}}
            """));

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static OfnIdValueRules rules(String schema) {
        return new OfnIdValueRules(CONTEXT, json(schema), JDKRegularExpressionFactory.getInstance());
    }

    @Test
    void readsFormatAndPatternPerField_andChecksUndefinedFieldsAsIris() {
        OfnIdValueRules rules = rules("""
                {"properties": {
                  "odkaz": {"type": "array", "items": {"type": "string", "format": "iri"}},
                  "kód": {"type": "string", "pattern": "^ex:[^/]+$"}}}
                """);

        assertThat(rules.problem("odkaz", "urn:x:1")).isNull();
        assertThat(rules.problem("odkaz", "a b")).isNotNull();
        assertThat(rules.problem("kód", "ex:a")).isNull();
        assertThat(rules.problem("kód", "https://example.org/a")).contains("neodpovídá vzoru");
        assertThat(rules.problem("iri", "https://example.org/a")).isNull();
        assertThat(rules.problem("iri", "a|b")).isEqualTo("hodnota není IRI");
    }

    @Test
    void aFieldWithTwoDifferentRules_failsStartup() {
        assertThatThrownBy(() -> rules("""
                {"properties": {"odkaz": {"type": "string", "format": "iri"},
                  "vnořený": {"properties": {"odkaz": {"type": "string", "pattern": "^ex:"}}}}}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("odkaz");
    }

    @Test
    void aFieldRuleTheCheckCannotRead_failsStartup() {
        assertThatThrownBy(() -> rules("""
                {"properties": {"odkaz": {"anyOf": [{"type": "string"}]}}}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("anyOf");
    }

    @Test
    void theBundledSchemaIsReadable() {
        assertThat(OfnSpecification.bundled().idValueRules().problem("způsob-získání-údaje", "způsoby-získání:vlastní"))
                .isNull();
    }
}
