package com.dia.ismdtoolbackend.ofn;

import com.dia.ismdtoolbackend.ofn.ConformanceIssue.Kind;
import com.dia.ismdtoolbackend.ofn.OfnContextIndex.Scope;
import com.dia.ismdtoolbackend.ofn.OfnContextIndex.Term;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Lets only OFN-conformant data through. Applied to the vocabulary document (in OFN context terms)
 * before any RDF is built, so non-conformant data is never transformed, not just left out of an export.
 * <ol>
 *   <li><b>Prune:</b> values with no term in the context, or with a shape the term does not allow
 *       (e.g. a non-IRI where the context says {@code @id}), are dropped. Shapes that only differ
 *       in wrapping (single value vs. {@code @set}) are normalised instead.</li>
 *   <li><b>Concepts:</b> each concept is checked against the schema variant of each of its types.
 *       A concept that fails is excluded, unless the only failures are missing required fields: such a
 *       concept is incomplete (a draft), kept and reported.</li>
 *   <li><b>Cascade:</b> references to excluded concepts are removed from the remaining ones, which
 *       are then checked again (e.g. a Vlastnost whose {@code definiční-obor} was excluded).</li>
 *   <li><b>Vocabulary:</b> the vocabulary's own fields are checked against the schema; missing
 *       required fields are reported as incomplete, other failures as invalid.</li>
 * </ol>
 * Every rule comes from the context and schema in {@link OfnSpecification}; nothing here names an
 * OFN term, so a new OFN version needs no code change.
 */
@Slf4j
public class OfnConformanceGate {

    private static final String LANGUAGE_TAG = "[a-zA-Z]{2,3}(-[a-zA-Z0-9]+)*";
    private static final String REQUIRED = "required";

    private final OfnSpecification specification;
    private final OfnContextIndex context;
    private final String idKey;
    private final String typeKey;

    public OfnConformanceGate(OfnSpecification specification) {
        this.specification = specification;
        this.context = specification.contextIndex();
        this.idKey = context.idAlias();
        this.typeKey = context.typeAlias();
    }

    public GateResult apply(ObjectNode vocabulary) {
        ObjectNode document = vocabulary.deepCopy();
        List<ConformanceIssue> issues = new ArrayList<>();

        pruneNode(document, rootScopes(), "", null, issues);
        filterConcepts(document, issues);
        checkVocabulary(document, issues);

        if (!issues.isEmpty()) {
            log.info("OFN conformance gate: {} value(s) dropped, {} concept(s) excluded, {} incomplete, {} reference(s) removed",
                    count(issues, Kind.VALUE_DROPPED), count(issues, Kind.CONCEPT_EXCLUDED),
                    count(issues, Kind.CONCEPT_INCOMPLETE), count(issues, Kind.REFERENCE_REMOVED));
        }
        return new GateResult(document, issues);
    }

    // ---------------------------------------------------------------- pruning

    private Deque<Scope> rootScopes() {
        Deque<Scope> scopes = new ArrayDeque<>();
        scopes.push(context.root());
        return scopes;
    }

    /**
     * Prunes one node object in place. {@code scopes} holds the active contexts, innermost first;
     * {@code conceptIri} is the concept the node belongs to (for reporting).
     */
    private void pruneNode(ObjectNode node, Deque<Scope> scopes, String location, String conceptIri,
                           List<ConformanceIssue> issues) {
        Deque<Scope> nodeScopes = pruneTypes(node, scopes, location, conceptIri, issues);

        for (String key : List.copyOf(names(node))) {
            String keyLocation = location.isEmpty() ? key : location + "/" + key;
            if (key.equals("@context") || key.equals(idKey) || key.equals(typeKey)) {
                continue; // @context is set by the builder; iri and typ are checked by the schema
            }
            Optional<Term> term = OfnContextIndex.resolve(nodeScopes, key);
            if (term.isEmpty()) {
                drop(node, key, keyLocation, conceptIri, issues);
                continue;
            }
            JsonNode pruned = pruneValue(term.get(), node.get(key), nodeScopes, keyLocation, conceptIri, issues);
            if (pruned == null || isEmptyContainer(pruned)) {
                node.remove(key);
            } else {
                node.set(key, pruned);
            }
        }
    }

    /** Drops types the context does not define and activates the type-scoped contexts of the rest. */
    private Deque<Scope> pruneTypes(ObjectNode node, Deque<Scope> scopes, String location, String conceptIri,
                                    List<ConformanceIssue> issues) {
        JsonNode types = node.get(typeKey);
        if (types == null) {
            return scopes;
        }
        Deque<Scope> result = new ArrayDeque<>(scopes);
        ArrayNode kept = JsonNodeFactory.instance.arrayNode();
        for (JsonNode type : asList(types)) {
            Optional<Term> typeTerm = type.isTextual()
                    ? OfnContextIndex.resolve(scopes, type.asText())
                    : Optional.empty();
            if (typeTerm.isEmpty() || typeTerm.get().isPropertyTerm()) {
                issues.add(new ConformanceIssue(Kind.VALUE_DROPPED, conceptIri, location + "/" + typeKey,
                        "typ " + type + " není definován v kontextu OFN", type.asText()));
                continue;
            }
            kept.add(type);
            if (typeTerm.get().nested() != null) {
                result.push(typeTerm.get().nested());
            }
        }
        node.set(typeKey, types.isArray() || kept.size() != 1 ? kept : kept.get(0));
        return result;
    }

    /** Returns the pruned value, or null when nothing of it is left. */
    private JsonNode pruneValue(Term term, JsonNode value, Deque<Scope> scopes, String location,
                                String conceptIri, List<ConformanceIssue> issues) {
        if ("@language".equals(term.container())) {
            return pruneLanguageMap(value, location, conceptIri, issues);
        }

        boolean isSet = "@set".equals(term.container()) || term.isReverse();
        if (!isSet && value.isArray()) {
            if (value.isEmpty()) {
                return null; // an empty list holds no value: the field is absent
            }
            if (value.size() != 1) {
                issues.add(new ConformanceIssue(Kind.VALUE_DROPPED, conceptIri, location,
                        "pole povoluje jedinou hodnotu, nalezeno " + value.size()));
                return null;
            }
            value = value.get(0);
        }

        Deque<Scope> valueScopes = scopes;
        if (term.nested() != null) {
            valueScopes = new ArrayDeque<>(scopes);
            valueScopes.push(term.nested());
        }
        ArrayNode kept = JsonNodeFactory.instance.arrayNode();
        int index = 0;
        for (JsonNode item : asList(value)) {
            String itemLocation = isSet ? location + "[" + label(item, index) + "]" : location;
            index++;
            JsonNode prunedItem = pruneItem(term, item, valueScopes, itemLocation, conceptIri, issues);
            if (prunedItem != null) {
                kept.add(prunedItem);
            }
        }
        if (isSet) {
            return kept;
        }
        return kept.isEmpty() ? null : kept.get(0);
    }

    private JsonNode pruneItem(Term term, JsonNode item, Deque<Scope> scopes, String location,
                               String conceptIri, List<ConformanceIssue> issues) {
        String problem;
        if (item.isObject()) {
            if (term.nested() == null && !term.isReverse()) {
                problem = "pole nepovoluje vnořený objekt";
            } else {
                ObjectNode node = (ObjectNode) item;
                String owner = conceptIri != null ? conceptIri : node.path(idKey).asText(null);
                pruneNode(node, scopes, location, owner, issues);
                return node.isEmpty() ? null : node;
            }
        } else {
            problem = literalProblem(term, item);
        }
        if (problem == null) {
            return item;
        }
        issues.add(new ConformanceIssue(Kind.VALUE_DROPPED, conceptIri, location, problem + ": " + item,
                item.isValueNode() ? item.asText() : null));
        return null;
    }

    /** Why a non-object value does not fit the term, or null when it fits. */
    private String literalProblem(Term term, JsonNode item) {
        String type = term.type();
        if ("@id".equals(type)) {
            return item.isTextual() ? specification.idValueRules().problem(term.name(), item.asText()) : "hodnota není IRI";
        }
        if ("xsd:boolean".equals(type)) {
            return item.isBoolean() ? null : "hodnota není logická hodnota (true/false)";
        }
        if (type != null && type.startsWith("xsd:")) {
            return item.isTextual() && !item.asText().isBlank() ? null : "hodnota není text (" + type + ")";
        }
        if (term.isReverse() || term.nested() != null) {
            return "pole očekává objekt";
        }
        return null;
    }

    /**
     * Keeps the non-blank texts of a language map. A value that is not text is dropped with an issue; a blank
     * text holds nothing, so it is dropped without one.
     */
    private JsonNode pruneLanguageMap(JsonNode value, String location, String conceptIri,
                                      List<ConformanceIssue> issues) {
        if (!value.isObject()) {
            issues.add(new ConformanceIssue(Kind.VALUE_DROPPED, conceptIri, location,
                    "očekáván text s jazykem, nalezeno " + value));
            return null;
        }
        ObjectNode kept = JsonNodeFactory.instance.objectNode();
        value.properties().forEach(entry -> {
            String language = entry.getKey();
            if (!language.matches(LANGUAGE_TAG)) {
                issues.add(new ConformanceIssue(Kind.VALUE_DROPPED, conceptIri, location,
                        "'" + language + "' není jazykový kód"));
                return;
            }
            ArrayNode texts = JsonNodeFactory.instance.arrayNode();
            for (JsonNode text : asList(entry.getValue())) {
                if (!text.isTextual()) {
                    issues.add(new ConformanceIssue(Kind.VALUE_DROPPED, conceptIri, location + "/" + language,
                            "hodnota není text: " + text, text.isValueNode() ? text.asText() : null));
                } else if (!text.asText().isBlank()) {
                    texts.add(text);
                }
            }
            if (!texts.isEmpty()) {
                kept.set(language, entry.getValue().isArray() ? texts : texts.get(0));
            }
        });
        return kept;
    }

    // ---------------------------------------------------------------- concepts

    private void filterConcepts(ObjectNode document, List<ConformanceIssue> issues) {
        JsonNode concepts = document.get(OfnSpecification.CONCEPTS_KEY);
        if (concepts == null || !concepts.isArray()) {
            return;
        }
        List<ObjectNode> kept = new ArrayList<>();
        concepts.forEach(concept -> {
            if (concept.isObject()) {
                kept.add((ObjectNode) concept);
            }
        });

        // Concepts are tracked as objects, not by IRI: a concept may lack an IRI or share one with another.
        Map<ObjectNode, ConformanceIssue> incomplete = new IdentityHashMap<>();
        Deque<ObjectNode> toCheck = new ArrayDeque<>(kept);
        while (!toCheck.isEmpty()) {
            Set<ObjectNode> newlyExcluded = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<String> excludedIris = new LinkedHashSet<>();
            for (ObjectNode concept : toCheck) {
                SchemaErrors errors = schemaErrors(concept);
                String iri = concept.path(idKey).asText(null);
                ConformanceIssue previous = incomplete.remove(concept);
                if (previous != null) {
                    issues.removeIf(issue -> issue == previous);
                }
                if (!errors.invalid().isEmpty()) {
                    newlyExcluded.add(concept);
                    if (iri != null) {
                        excludedIris.add(context.expand(iri));
                    }
                    issues.add(new ConformanceIssue(Kind.CONCEPT_EXCLUDED, iri, conceptLocation(concept),
                            "pojem nesplňuje OFN schéma: " + String.join("; ", errors.all())));
                } else if (!errors.missing().isEmpty()) {
                    ConformanceIssue issue = new ConformanceIssue(Kind.CONCEPT_INCOMPLETE, iri, conceptLocation(concept),
                            "pojem není úplný: " + String.join("; ", errors.missing()));
                    incomplete.put(concept, issue);
                    issues.add(issue);
                }
            }
            kept.removeIf(newlyExcluded::contains);

            toCheck.clear();
            if (!excludedIris.isEmpty()) {
                for (ObjectNode concept : kept) {
                    if (removeReferences(concept, excludedIris, issues)) {
                        toCheck.add(concept);
                    }
                }
            }
        }

        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        kept.forEach(result::add);
        document.set(OfnSpecification.CONCEPTS_KEY, result);
    }

    /** Schema errors split into missing required fields (completeness) and every other failure. */
    private record SchemaErrors(List<String> missing, List<String> invalid) {
        List<String> all() {
            List<String> all = new ArrayList<>(invalid);
            all.addAll(missing);
            return all;
        }
    }

    private static SchemaErrors split(Collection<ValidationMessage> messages) {
        Set<String> missing = new TreeSet<>();
        Set<String> invalid = new TreeSet<>();
        for (ValidationMessage message : messages) {
            if (REQUIRED.equals(message.getType())) {
                missing.add(missingField(message));
            } else {
                invalid.add(message.getMessage());
            }
        }
        return new SchemaErrors(List.copyOf(missing), List.copyOf(invalid));
    }

    /** "chybí povinné pole X", with the parent path when the field is nested. */
    private static String missingField(ValidationMessage message) {
        String parent = message.getInstanceLocation() == null ? "" : message.getInstanceLocation().toString();
        String field = message.getProperty() != null ? message.getProperty() : String.valueOf(message.getArguments()[0]);
        String where = parent.equals("$") || parent.isEmpty() ? "" : " v " + parent.replaceFirst("^\\$\\.?", "");
        return "chybí povinné pole " + field + where;
    }

    /**
     * Schema errors of a concept against the variant of each of its types. A concept none of whose types names a
     * variant is invalid as a whole: checking it against every variant would only list their mixed requirements.
     */
    private SchemaErrors schemaErrors(ObjectNode concept) {
        List<JsonSchema> variants = new ArrayList<>();
        for (JsonNode type : asList(concept.path(typeKey))) {
            JsonSchema variant = specification.conceptSchemasByType().get(type.asText());
            if (variant != null) {
                variants.add(variant);
            }
        }
        if (variants.isEmpty()) {
            return new SchemaErrors(List.of(), List.of("typ neodpovídá žádné variantě OFN (očekáván jeden z: "
                    + String.join(", ", specification.conceptSchemasByType().keySet()) + ")"));
        }
        List<ValidationMessage> messages = new ArrayList<>();
        for (JsonSchema variant : variants) {
            messages.addAll(variant.validate(concept));
        }
        return split(messages);
    }

    /**
     * Removes the concept's references to the excluded concepts, compared as full IRIs (a reference may be a
     * compact IRI), and returns true when anything was removed. Only direct fields reference concepts: the one
     * nested {@code @id} term holds a dataset IRI.
     *
     * @param targets full IRIs of the excluded concepts
     */
    private boolean removeReferences(ObjectNode concept, Set<String> targets, List<ConformanceIssue> issues) {
        Deque<Scope> scopes = conceptScopes(concept);
        String iri = concept.path(idKey).asText(null);
        boolean changed = false;
        for (String key : List.copyOf(names(concept))) {
            Optional<Term> term = OfnContextIndex.resolve(scopes, key);
            if (term.isEmpty() || !"@id".equals(term.get().type())) {
                continue;
            }
            JsonNode value = concept.get(key);
            ArrayNode kept = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : asList(value)) {
                if (item.isTextual() && targets.contains(context.expand(item.asText()))) {
                    issues.add(new ConformanceIssue(Kind.REFERENCE_REMOVED, iri, conceptLocation(concept) + "/" + key,
                            "odkaz na vyřazený pojem " + item.asText(), item.asText()));
                    changed = true;
                } else {
                    kept.add(item);
                }
            }
            if (kept.isEmpty()) {
                concept.remove(key);
            } else if (kept.size() != asList(value).size()) {
                concept.set(key, value.isArray() ? kept : kept.get(0));
            }
        }
        return changed;
    }

    private Deque<Scope> conceptScopes(ObjectNode concept) {
        Deque<Scope> scopes = rootScopes();
        Term conceptsTerm = context.root().terms.get(OfnSpecification.CONCEPTS_KEY);
        if (conceptsTerm != null && conceptsTerm.nested() != null) {
            scopes.push(conceptsTerm.nested());
        }
        for (JsonNode type : asList(concept.path(typeKey))) {
            OfnContextIndex.resolve(scopes, type.asText())
                    .map(Term::nested)
                    .ifPresent(scopes::push);
        }
        return scopes;
    }

    // ---------------------------------------------------------------- vocabulary

    private void checkVocabulary(ObjectNode document, List<ConformanceIssue> issues) {
        ObjectNode vocabularyOnly = document.deepCopy();
        vocabularyOnly.set(OfnSpecification.CONCEPTS_KEY, JsonNodeFactory.instance.arrayNode());
        SchemaErrors errors = split(specification.schema().validate(vocabularyOnly));
        errors.invalid().forEach(message -> issues.add(new ConformanceIssue(Kind.VOCABULARY_INVALID, null, "", message)));
        errors.missing().forEach(message -> issues.add(new ConformanceIssue(Kind.VOCABULARY_INCOMPLETE, null, "", message)));
    }

    // ---------------------------------------------------------------- helpers

    private void drop(ObjectNode node, String key, String location, String conceptIri,
                      List<ConformanceIssue> issues) {
        issues.add(new ConformanceIssue(Kind.VALUE_DROPPED, conceptIri, location, "pole není definováno v kontextu OFN"));
        node.remove(key);
    }

    private String conceptLocation(ObjectNode concept) {
        return OfnSpecification.CONCEPTS_KEY + "[" + label(concept, 0) + "]";
    }

    /**
     * Local name of a node's IRI (after the last {@code /} or {@code #}, e.g. "řidič"), the whole IRI when
     * nothing follows, or the node's array index when it has no IRI.
     */
    private String label(JsonNode item, int index) {
        String iri = item.path(idKey).asText("");
        if (iri.isEmpty()) {
            return String.valueOf(index);
        }
        String name = OfnIris.localName(iri);
        return name.isEmpty() ? iri : name;
    }

    private static boolean isEmptyContainer(JsonNode value) {
        return value.isContainerNode() && value.isEmpty();
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<JsonNode> asList(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            return List.of(value);
        }
        List<JsonNode> items = new ArrayList<>();
        value.forEach(items::add);
        return items;
    }

    private static long count(List<ConformanceIssue> issues, Kind kind) {
        return issues.stream().filter(issue -> issue.kind() == kind).count();
    }
}
