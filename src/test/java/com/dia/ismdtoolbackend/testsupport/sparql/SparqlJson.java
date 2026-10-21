package com.dia.ismdtoolbackend.testsupport.sparql;

import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Builds SPARQL 1.1 JSON results the way the Virtuoso endpoints send them: datatyped values as
 * {@code typed-literal} and booleans as {@code xsd:integer} 0/1 ({@link #bool}). Use
 * {@link #xsdBoolean} only for a test that needs a real {@code xsd:boolean}.
 *
 * <pre>{@code
 * SparqlJson.select("s", "label")
 *         .row(uri("s", "https://x/1"), literal("label", "Osoba", "cs"))
 *         .toJson();
 * }</pre>
 */
public final class SparqlJson {

    public static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    private final List<String> vars;
    private final List<List<Binding>> rows = new ArrayList<>();

    private SparqlJson(List<String> vars) {
        this.vars = vars;
    }

    /** A result with these projected variables and no rows yet. */
    public static SparqlJson select(String... vars) {
        return new SparqlJson(List.of(vars));
    }

    /** One solution. A {@code null} binding leaves its variable unbound, as an unmatched OPTIONAL does. */
    public SparqlJson row(Binding... bindings) {
        List<Binding> row = new ArrayList<>();
        for (Binding b : bindings) {
            if (b != null) {
                row.add(b);
            }
        }
        rows.add(row);
        return this;
    }

    public String toJson() {
        String head = vars.stream().map(SparqlJson::quote).collect(Collectors.joining(","));
        String bindings = rows.stream()
                .map(row -> row.stream().map(Binding::toJson).collect(Collectors.joining(",", "{", "}")))
                .collect(Collectors.joining(",\n"));
        return "{\"head\":{\"vars\":[" + head + "]},\"results\":{\"bindings\":[\n" + bindings + "\n]}}";
    }

    /** The same result parsed by Jena, for mappers that take a {@link ResultSet}. */
    public ResultSet toResultSet() {
        return ResultSetFactory.fromJSON(new ByteArrayInputStream(toJson().getBytes(StandardCharsets.UTF_8)));
    }

    public static Binding uri(String var, String iri) {
        return iri == null ? null : new Binding(var, "\"type\":\"uri\",\"value\":" + quote(iri));
    }

    public static Binding literal(String var, String value) {
        return value == null ? null : new Binding(var, "\"type\":\"literal\",\"value\":" + quote(value));
    }

    public static Binding literal(String var, String value, String lang) {
        return value == null ? null : new Binding(var,
                "\"type\":\"literal\",\"xml:lang\":" + quote(lang) + ",\"value\":" + quote(value));
    }

    /** A datatyped literal; {@code datatype} is a full IRI or an {@code xsd:} local name. */
    public static Binding typed(String var, String value, String datatype) {
        if (value == null) {
            return null;
        }
        String iri = datatype.startsWith("xsd:") ? XSD + datatype.substring(4) : datatype;
        return new Binding(var, "\"type\":\"typed-literal\",\"datatype\":" + quote(iri)
                + ",\"value\":" + quote(value));
    }

    public static Binding integer(String var, long value) {
        return typed(var, Long.toString(value), XSD + "integer");
    }

    /** A boolean as Virtuoso projects it: {@code "1"} / {@code "0"} typed {@code xsd:integer}. */
    public static Binding bool(String var, boolean value) {
        return integer(var, value ? 1 : 0);
    }

    /** A real {@code xsd:boolean}; the NKD/NKOD/e-Sbírka endpoints do not send these. */
    public static Binding xsdBoolean(String var, boolean value) {
        return typed(var, Boolean.toString(value), XSD + "boolean");
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** One variable's value in a solution. */
    public static final class Binding {
        private final String var;
        private final String body;

        private Binding(String var, String body) {
            this.var = Objects.requireNonNull(var);
            this.body = body;
        }

        private String toJson() {
            return quote(var) + ":{" + body + "}";
        }
    }
}
