package com.dia.ismdtoolbackend.ofn;

import com.dia.ismdtoolbackend.ofn.OfnContextIndex.Scope;
import com.dia.ismdtoolbackend.ofn.OfnContextIndex.Term;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/**
 * Orders the keys of a document as the context lists its terms: {@code @context}, the id and type
 * aliases first, then each object's terms in context order. Unknown keys keep their order at the end.
 * Purely presentational: JSON-LD meaning does not depend on key order.
 */
public final class OfnDocumentOrder {

    private final OfnContextIndex context;

    public OfnDocumentOrder(OfnContextIndex context) {
        this.context = context;
    }

    /** Returns an ordered copy. */
    public ObjectNode apply(ObjectNode document) {
        Deque<Scope> scopes = new ArrayDeque<>();
        scopes.push(context.root());
        return orderNode(document, scopes);
    }

    private ObjectNode orderNode(ObjectNode node, Deque<Scope> scopes) {
        Deque<Scope> nodeScopes = withTypeScopes(node, scopes);

        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        keys.sort(Comparator.comparingInt((String key) -> rank(key, nodeScopes)));

        ObjectNode ordered = JsonNodeFactory.instance.objectNode();
        for (String key : keys) {
            JsonNode value = node.get(key);
            Optional<Term> term = OfnContextIndex.resolve(nodeScopes, key);
            Deque<Scope> valueScopes = nodeScopes;
            if (term.isPresent() && term.get().nested() != null) {
                valueScopes = new ArrayDeque<>(nodeScopes);
                valueScopes.push(term.get().nested());
            }
            ordered.set(key, orderValue(value, valueScopes));
        }
        return ordered;
    }

    private JsonNode orderValue(JsonNode value, Deque<Scope> scopes) {
        if (value instanceof ObjectNode object) {
            return orderNode(object, scopes);
        }
        if (value instanceof ArrayNode array) {
            ArrayNode ordered = JsonNodeFactory.instance.arrayNode();
            array.forEach(item -> ordered.add(orderValue(item, scopes)));
            return ordered;
        }
        return value;
    }

    /** Position of a key: keywords and aliases first, then by place in the scope defining the term. */
    private int rank(String key, Deque<Scope> scopes) {
        if (key.equals("@context")) {
            return 0;
        }
        if (key.equals(context.idAlias())) {
            return 1;
        }
        if (key.equals(context.typeAlias())) {
            return 2;
        }
        int depth = 0;
        for (Scope scope : scopes) { // innermost first
            int index = new ArrayList<>(scope.terms.keySet()).indexOf(key);
            if (index >= 0) {
                return 1_000 + depth * 1_000 + index;
            }
            depth++;
        }
        return Integer.MAX_VALUE;
    }

    private Deque<Scope> withTypeScopes(ObjectNode node, Deque<Scope> scopes) {
        JsonNode types = node.get(context.typeAlias());
        if (types == null) {
            return scopes;
        }
        Deque<Scope> result = new ArrayDeque<>(scopes);
        List<JsonNode> list = new ArrayList<>();
        if (types.isArray()) {
            types.forEach(list::add);
        } else {
            list.add(types);
        }
        for (JsonNode type : list) {
            OfnContextIndex.resolve(scopes, type.asText()).map(Term::nested).ifPresent(result::push);
        }
        return result;
    }
}
