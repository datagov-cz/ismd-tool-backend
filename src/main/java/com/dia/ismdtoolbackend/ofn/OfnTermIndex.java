package com.dia.ismdtoolbackend.ofn;

import com.dia.ismdtoolbackend.ofn.OfnContextIndex.Scope;
import com.dia.ismdtoolbackend.ofn.OfnContextIndex.Term;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The OFN context read backwards: which term writes a given RDF predicate or type in a given scope,
 * plus the output shapes the schema expects (CURIE values, per-language arrays). Built from the
 * context and schema only.
 */
final class OfnTermIndex {

    private static final Pattern PREFIX_PATTERN = Pattern.compile("^\\^([^:\\[\\]^$]+):");

    private final OfnContextIndex context;
    private final Map<Scope, Map<String, List<Term>>> propertiesByIri = new IdentityHashMap<>();
    private final Map<Scope, Map<String, Term>> typesByIri = new IdentityHashMap<>();
    private final Map<String, Integer> termOrder = new HashMap<>();
    private final Map<String, Set<String>> variantKeys = new HashMap<>();
    private final Map<String, JsonNode> conceptProperties = new HashMap<>();
    private final Map<String, JsonNode> vocabularyProperties = new HashMap<>();
    private final Term conceptsTerm;

    OfnTermIndex(OfnSpecification specification) {
        this.context = specification.contextIndex();
        index(context.root());
        int position = 0;
        for (Term term : context.allTerms().values()) {
            termOrder.putIfAbsent(term.name(), position++);
        }
        this.conceptsTerm = context.root().terms.get(OfnSpecification.CONCEPTS_KEY);
        readSchema(specification.schemaDocument());
    }

    private void index(Scope scope) {
        Map<String, List<Term>> properties = new HashMap<>();
        Map<String, Term> types = new HashMap<>();
        for (Term term : scope.terms.values()) {
            String id = term.id();
            if (id == null || id.startsWith("@") || term.isReverse()) {
                continue;
            }
            String iri = context.expand(id);
            if (term.isPropertyTerm()) {
                properties.computeIfAbsent(iri, key -> new ArrayList<>()).add(term);
            } else {
                types.put(iri, term);
            }
        }
        propertiesByIri.put(scope, properties);
        typesByIri.put(scope, types);
        scope.children.values().forEach(this::index);
    }

    private void readSchema(JsonNode schema) {
        schema.path("properties").properties().forEach(e -> vocabularyProperties.put(e.getKey(), e.getValue()));
        for (JsonNode variant : schema.path("properties").path(OfnSpecification.CONCEPTS_KEY).path("items").path("anyOf")) {
            Set<String> keys = new HashSet<>();
            variant.path("properties").properties().forEach(e -> {
                keys.add(e.getKey());
                conceptProperties.putIfAbsent(e.getKey(), e.getValue());
            });
            variantKeys.put(variant.path("title").asText(), keys);
        }
    }

    /** The root term listing the concepts ({@code pojmy}, the reverse of {@code skos:inScheme}). */
    Term conceptsTerm() {
        return conceptsTerm;
    }

    /** The full IRI of the reverse property linking a concept to its vocabulary. */
    String conceptsReverseIri() {
        return context.expand(conceptsTerm.definition().path("@reverse").asText());
    }

    /** The terms writing the predicate in the innermost scope that defines any, or an empty list. */
    List<Term> propertyTerms(Deque<Scope> scopes, String predicateIri) {
        for (Scope scope : scopes) {
            List<Term> terms = propertiesByIri.get(scope).get(predicateIri);
            if (terms != null) {
                return terms;
            }
        }
        return List.of();
    }

    Optional<Term> typeTerm(Deque<Scope> scopes, String typeIri) {
        for (Scope scope : scopes) {
            Term term = typesByIri.get(scope).get(typeIri);
            if (term != null) {
                return Optional.of(term);
            }
        }
        return Optional.empty();
    }

    /** Position of a term name in the context, for a stable order of {@code typ} values. */
    int order(String termName) {
        return termOrder.getOrDefault(termName, Integer.MAX_VALUE);
    }

    /**
     * Picks one of several terms sharing a predicate (e.g. {@code rdfs:subPropertyOf} is both
     * {@code nadřazená-vlastnost} and {@code nadřazený-vztah}): the one the schema variant of one of the
     * node's types declares, else the first.
     */
    Term choose(List<Term> candidates, Collection<String> typeNames) {
        for (Term candidate : candidates) {
            for (String type : typeNames) {
                if (variantKeys.getOrDefault(type, Set.of()).contains(candidate.name())) {
                    return candidate;
                }
            }
        }
        return candidates.get(0);
    }

    /** True when the schema wants every language of this language map as an array. */
    boolean languageValuesAreArrays(String key, boolean concept) {
        JsonNode property = (concept ? conceptProperties : vocabularyProperties).get(key);
        return property != null && "array".equals(property.path("additionalProperties").path("type").asText());
    }

    /**
     * The IRI as the schema expects it under the key: a compact IRI when the schema's pattern requires
     * a prefix (e.g. {@code ^způsoby-získání:}) or for an XSD datatype, else the full IRI.
     */
    String iriValue(String key, String iri, boolean concept) {
        JsonNode property = (concept ? conceptProperties : vocabularyProperties).get(key);
        if (property != null) {
            String pattern = property.path("pattern").asText(property.path("items").path("pattern").asText(""));
            Matcher matcher = PREFIX_PATTERN.matcher(pattern);
            if (matcher.find()) {
                String namespace = context.prefixes().get(matcher.group(1));
                if (namespace != null && iri.startsWith(namespace)) {
                    return matcher.group(1) + ":" + iri.substring(namespace.length());
                }
            }
        }
        String xsd = context.prefixes().get("xsd");
        if (xsd != null && iri.startsWith(xsd) && iri.length() > xsd.length()) {
            return "xsd:" + iri.substring(xsd.length());
        }
        return iri;
    }

    String compactIri(String iri) {
        return context.compactIri(iri);
    }
}