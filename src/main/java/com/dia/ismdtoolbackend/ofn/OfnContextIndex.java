package com.dia.ismdtoolbackend.ofn;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * Read-only view of a JSON-LD context: prefixes, term definitions and their nested
 * (property-scoped or type-scoped) contexts. Every term is addressed by its path,
 * e.g. {@code pojmy/definující-nelegislativní-zdroj/Digitální objekt/url}.
 * <p>
 * Nothing here knows OFN term names, so a modified context needs no code change.
 */
public final class OfnContextIndex {

    /** One term definition and the context it lives in. */
    public record Term(String path, String name, JsonNode definition, Scope scope) {

        public String id() {
            return definition.isTextual() ? definition.asText() : definition.path("@id").asText(null);
        }

        public String type() {
            return definition.path("@type").asText(null);
        }

        public String container() {
            return definition.path("@container").asText(null);
        }

        public boolean isReverse() {
            return definition.has("@reverse");
        }

        /** Nested context of this term, or null. */
        public Scope nested() {
            return scope.children.get(name);
        }

        /** By convention of the OFN contexts, capitalised terms name types (classes) and the rest name properties. */
        public boolean isPropertyTerm() {
            return name.isEmpty() || !Character.isUpperCase(name.codePointAt(0));
        }
    }

    /** One level of context: its term definitions and the nested contexts they open. */
    public static final class Scope {
        public final String path;
        public final Map<String, Term> terms = new LinkedHashMap<>();
        public final Map<String, Scope> children = new HashMap<>();

        Scope(String path) {
            this.path = path;
        }
    }

    private final Scope root = new Scope("");
    private final Map<String, String> prefixes = new LinkedHashMap<>();
    private final Map<String, Term> allTerms = new LinkedHashMap<>();

    public OfnContextIndex(JsonNode contextDocument) {
        JsonNode context = contextDocument.path("@context");
        if (!context.isObject()) {
            throw new IllegalArgumentException("Context document has no @context object");
        }
        index(context, root);
    }

    private void index(JsonNode context, Scope scope) {
        context.properties().forEach(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (key.startsWith("@")) {
                return;
            }
            if (scope == root && isPrefixDefinition(value)) {
                prefixes.put(key, value.asText());
                return;
            }
            String path = scope.path.isEmpty() ? key : scope.path + "/" + key;
            Term term = new Term(path, key, value, scope);
            scope.terms.put(key, term);
            allTerms.put(path, term);
            if (value.isObject() && value.path("@context").isObject()) {
                Scope child = new Scope(path);
                scope.children.put(key, child);
                index(value.get("@context"), child);
            }
        });
    }

    private static boolean isPrefixDefinition(JsonNode value) {
        if (!value.isTextual()) {
            return false;
        }
        String iri = value.asText();
        return iri.endsWith("/") || iri.endsWith("#");
    }

    public Scope root() {
        return root;
    }

    public Map<String, String> prefixes() {
        return Collections.unmodifiableMap(prefixes);
    }

    /** The root term aliasing {@code @id} (e.g. "iri"). */
    public String idAlias() {
        return aliasOf("@id");
    }

    /** The root term aliasing {@code @type} (e.g. "typ"). */
    public String typeAlias() {
        return aliasOf("@type");
    }

    private String aliasOf(String keyword) {
        return root.terms.values().stream()
                .filter(term -> keyword.equals(term.id()))
                .map(Term::name)
                .findFirst()
                .orElse(keyword);
    }

    /** All term definitions by path, in context order. */
    public Map<String, Term> allTerms() {
        return Collections.unmodifiableMap(allTerms);
    }

    /** Resolves a term against the active scopes, innermost first. */
    public static Optional<Term> resolve(Deque<Scope> activeScopes, String name) {
        for (Scope scope : activeScopes) {
            Term term = scope.terms.get(name);
            if (term != null) {
                return Optional.of(term);
            }
        }
        return Optional.empty();
    }

    /** The IRI as a compact IRI with the longest matching context prefix, or unchanged. */
    public String compactIri(String iri) {
        String bestPrefix = null;
        String bestNamespace = "";
        for (Map.Entry<String, String> prefix : prefixes.entrySet()) {
            String namespace = prefix.getValue();
            if (iri.startsWith(namespace) && iri.length() > namespace.length()
                    && namespace.length() > bestNamespace.length()) {
                bestPrefix = prefix.getKey();
                bestNamespace = namespace;
            }
        }
        return bestPrefix == null ? iri : bestPrefix + ":" + iri.substring(bestNamespace.length());
    }

    /** The full IRI of a compact IRI whose prefix the context defines; any other value unchanged. */
    public String expand(String value) {
        int colon = value.indexOf(':');
        if (colon <= 0) {
            return value;
        }
        String namespace = prefixes.get(value.substring(0, colon));
        return namespace == null ? value : namespace + value.substring(colon + 1);
    }
}
