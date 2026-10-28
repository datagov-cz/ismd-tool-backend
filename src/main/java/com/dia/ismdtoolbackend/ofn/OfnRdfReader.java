package com.dia.ismdtoolbackend.ofn;

import com.dia.ismdtoolbackend.ofn.ConformanceIssue.Kind;
import com.dia.ismdtoolbackend.ofn.OfnContextIndex.Scope;
import com.dia.ismdtoolbackend.ofn.OfnContextIndex.Term;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.util.*;

/**
 * Reads a vocabulary graph into an OFN document, term by term through the context read backwards.
 * Nothing is repaired: a triple the context cannot express (unknown predicate or type, wrong datatype,
 * text without a language, a literal where an IRI belongs) is left out and reported as
 * {@link Kind#VALUE_DROPPED}. For conformant RDF the document is the same graph.
 * <p>
 * The concepts ({@code pojmy}) are the subjects in the vocabulary's {@code skos:inScheme} whose IRI lies
 * under the vocabulary IRI.
 */
public final class OfnRdfReader {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** The document and everything that could not be expressed in it. */
    public record Result(ObjectNode document, List<ConformanceIssue> issues) {
        public Result {
            issues = List.copyOf(issues);
        }
    }

    private enum Level { VOCABULARY, CONCEPT, NESTED }

    private final OfnSpecification specification;
    private final OfnTermIndex terms;
    private final String idKey;
    private final String typeKey;
    private final Set<String> toleratedTypeIris;

    /** @param toleratedTypeIris type IRIs written to {@code typ} as compact IRIs although the context lacks them */
    public OfnRdfReader(OfnSpecification specification, Set<String> toleratedTypeIris) {
        this.specification = specification;
        this.terms = new OfnTermIndex(specification);
        this.idKey = specification.contextIndex().idAlias();
        this.typeKey = specification.contextIndex().typeAlias();
        this.toleratedTypeIris = Set.copyOf(toleratedTypeIris);
    }

    /**
     * The vocabulary with all its concepts. Other subjects (references kept as context, e.g. a foreign
     * concept a range points at) are not part of the vocabulary and are left out without an issue.
     */
    public Result readVocabulary(Model model, String vocabularyIri) {
        Reading reading = new Reading(model, vocabularyIri);
        ObjectNode document = reading.vocabulary();
        List<Resource> concepts = reading.memberConcepts();
        reading.concepts(document, concepts);
        return new Result(document, reading.issues);
    }

    /** The vocabulary node with only the given concepts, as they are in the graph. */
    public Result readConcepts(Model model, String vocabularyIri, Collection<String> conceptIris) {
        Reading reading = new Reading(model, vocabularyIri);
        ObjectNode document = reading.vocabulary();
        List<Resource> concepts = conceptIris.stream().distinct().sorted().map(model::createResource).toList();
        for (Resource concept : concepts) {
            reading.requireMembership(concept);
        }
        reading.concepts(document, concepts);
        return new Result(document, reading.issues);
    }

    /** State of one read. */
    private final class Reading {
        private final Model model;
        private final Resource vocabulary;
        private final Property inScheme;
        private final List<ConformanceIssue> issues = new ArrayList<>();
        private final Set<Resource> visiting = new HashSet<>();

        Reading(Model model, String vocabularyIri) {
            this.model = model;
            this.vocabulary = model.createResource(vocabularyIri);
            this.inScheme = model.createProperty(terms.conceptsReverseIri());
        }

        ObjectNode vocabulary() {
            ObjectNode document = JSON.objectNode();
            document.put("@context", specification.contextUrl());
            Deque<Scope> scopes = new ArrayDeque<>();
            scopes.push(specification.contextIndex().root());
            render(document, vocabulary, scopes, "", null, Level.VOCABULARY);
            return document;
        }

        List<Resource> memberConcepts() {
            List<Resource> concepts = new ArrayList<>();
            for (Resource subject : model.listSubjectsWithProperty(inScheme, vocabulary).toList()) {
                if (subject.isURIResource() && OfnIris.isOwnedBy(subject.getURI(), vocabulary.getURI())) {
                    concepts.add(subject);
                } else {
                    issues.add(new ConformanceIssue(Kind.CONCEPT_EXCLUDED, iri(subject), conceptLocation(subject),
                            "pojem neleží ve jmenném prostoru slovníku " + vocabulary.getURI() + "/"));
                }
            }
            concepts.sort(Comparator.comparing(Resource::getURI));
            return concepts;
        }

        void requireMembership(Resource concept) {
            if (!model.contains(concept, inScheme, vocabulary)) {
                issues.add(new ConformanceIssue(Kind.CONCEPT_EXCLUDED, concept.getURI(), conceptLocation(concept),
                        "pojem není zařazen do slovníku (chybí " + terms.compactIri(inScheme.getURI()) + ")"));
            } else if (!OfnIris.isOwnedBy(concept.getURI(), vocabulary.getURI())) {
                issues.add(new ConformanceIssue(Kind.CONCEPT_EXCLUDED, concept.getURI(), conceptLocation(concept),
                        "pojem neleží ve jmenném prostoru slovníku " + vocabulary.getURI() + "/"));
            }
        }

        void concepts(ObjectNode document, List<Resource> concepts) {
            ArrayNode list = document.putArray(OfnSpecification.CONCEPTS_KEY);
            Deque<Scope> scopes = new ArrayDeque<>();
            scopes.push(specification.contextIndex().root());
            scopes.push(terms.conceptsTerm().nested());
            for (Resource concept : concepts) {
                ObjectNode node = JSON.objectNode();
                render(node, concept, scopes, conceptLocation(concept), concept.getURI(), Level.CONCEPT);
                list.add(node);
            }
        }

        // ------------------------------------------------------------ nodes

        private void render(ObjectNode out, Resource subject, Deque<Scope> scopes, String location,
                            String conceptIri, Level level) {
            if (subject.isURIResource()) {
                out.put(idKey, subject.getURI());
            }
            Deque<Scope> nodeScopes = new ArrayDeque<>(scopes);
            List<String> typeNames = renderTypes(out, subject, nodeScopes, location, conceptIri, level);

            Map<String, List<RDFNode>> byPredicate = new TreeMap<>();
            for (Statement statement : subject.listProperties().toList()) {
                Property predicate = statement.getPredicate();
                if (predicate.equals(RDF.type)
                        || (level == Level.CONCEPT && predicate.equals(inScheme) && statement.getObject().equals(vocabulary))) {
                    continue;
                }
                byPredicate.computeIfAbsent(predicate.getURI(), key -> new ArrayList<>()).add(statement.getObject());
            }
            byPredicate.forEach((predicate, objects) -> {
                List<Term> candidates = terms.propertyTerms(nodeScopes, predicate);
                if (candidates.isEmpty()) {
                    String name = terms.compactIri(predicate);
                    objects.forEach(object -> issue(conceptIri, path(location, name),
                            "vlastnost " + name + " není definována v kontextu OFN", object));
                    return;
                }
                Term term = terms.choose(candidates, typeNames);
                JsonNode value = values(term, objects, nodeScopes, path(location, term.name()), conceptIri, level);
                if (value != null) {
                    out.set(term.name(), value);
                }
            });
        }

        private List<String> renderTypes(ObjectNode out, Resource subject, Deque<Scope> scopes, String location,
                                         String conceptIri, Level level) {
            Deque<Scope> lookup = new ArrayDeque<>(scopes);
            List<Term> known = new ArrayList<>();
            List<String> tolerated = new ArrayList<>();
            for (Statement statement : subject.listProperties(RDF.type).toList()) {
                RDFNode type = statement.getObject();
                Optional<Term> term = type.isURIResource() ? terms.typeTerm(lookup, type.asResource().getURI()) : Optional.empty();
                if (term.isPresent()) {
                    known.add(term.get());
                } else if (type.isURIResource() && toleratedTypeIris.contains(type.asResource().getURI())) {
                    tolerated.add(terms.compactIri(type.asResource().getURI()));
                } else {
                    issue(conceptIri, path(location, typeKey), "typ " + label(type) + " není definován v kontextu OFN", type);
                }
            }
            known.sort(Comparator.comparingInt((Term term) -> terms.order(term.name())));
            Collections.sort(tolerated);
            known.stream().map(Term::nested).filter(Objects::nonNull).forEach(scopes::push);

            List<String> names = new ArrayList<>(known.stream().map(Term::name).toList());
            List<String> all = new ArrayList<>(names);
            all.addAll(tolerated);
            if (level == Level.NESTED && all.size() == 1) {
                out.put(typeKey, all.get(0));
            } else if (!all.isEmpty()) {
                ArrayNode array = out.putArray(typeKey);
                all.forEach(array::add);
            }
            return names;
        }

        // ------------------------------------------------------------ values

        private JsonNode values(Term term, List<RDFNode> objects, Deque<Scope> scopes, String location,
                                String conceptIri, Level level) {
            if ("@language".equals(term.container())) {
                return languageMap(term, objects, location, conceptIri, level);
            }
            List<RDFNode> sorted = new ArrayList<>(objects);
            sorted.sort(Comparator.comparing(RDFNode::toString));
            ArrayNode items = JSON.arrayNode();
            for (RDFNode object : sorted) {
                JsonNode item = item(term, object, scopes, location, conceptIri, level);
                if (item != null) {
                    items.add(item);
                }
            }
            if (items.isEmpty()) {
                return null;
            }
            return "@set".equals(term.container()) || items.size() > 1 ? items : items.get(0);
        }

        private JsonNode languageMap(Term term, List<RDFNode> objects, String location, String conceptIri, Level level) {
            Map<String, List<String>> texts = new TreeMap<>();
            for (RDFNode object : objects) {
                if (object.isLiteral() && !object.asLiteral().getLanguage().isEmpty()) {
                    texts.computeIfAbsent(object.asLiteral().getLanguage(), key -> new ArrayList<>())
                            .add(object.asLiteral().getLexicalForm());
                } else {
                    issue(conceptIri, location, "očekáván text s jazykem, nalezeno " + label(object), object);
                }
            }
            if (texts.isEmpty()) {
                return null;
            }
            boolean arrays = terms.languageValuesAreArrays(term.name(), level == Level.CONCEPT);
            ObjectNode map = JSON.objectNode();
            texts.forEach((language, values) -> {
                Collections.sort(values);
                if (arrays || values.size() > 1) {
                    ArrayNode array = map.putArray(language);
                    values.forEach(array::add);
                } else {
                    map.put(language, values.get(0));
                }
            });
            return map;
        }

        private JsonNode item(Term term, RDFNode object, Deque<Scope> scopes, String location,
                              String conceptIri, Level level) {
            String type = term.type();
            if ("@id".equals(type)) {
                if (object.isLiteral()) {
                    return issue(conceptIri, location, "hodnota není IRI: " + label(object), object);
                }
                if (term.nested() != null && (object.isAnon() || hasProperties(object.asResource()))) {
                    return nested(term, object.asResource(), scopes, location, conceptIri);
                }
                if (object.isAnon()) {
                    return issue(conceptIri, location, "hodnota je prázdný uzel, očekáváno IRI", object);
                }
                return JSON.textNode(terms.iriValue(term.name(), object.asResource().getURI(), level == Level.CONCEPT));
            }
            if (type != null) {
                return typedLiteral(type, object, location, conceptIri);
            }
            if (term.nested() != null) {
                return object.isLiteral()
                        ? issue(conceptIri, location, "očekáván objekt, nalezen literál " + label(object), object)
                        : nested(term, object.asResource(), scopes, location, conceptIri);
            }
            return object.isLiteral()
                    ? JSON.textNode(object.asLiteral().getLexicalForm())
                    : issue(conceptIri, location, "očekáván literál, nalezeno " + label(object), object);
        }

        private JsonNode typedLiteral(String type, RDFNode object, String location, String conceptIri) {
            String expected = specification.contextIndex().expand(type);
            if (!object.isLiteral()) {
                return issue(conceptIri, location, "očekáván literál typu " + type + ", nalezeno " + label(object), object);
            }
            Literal literal = object.asLiteral();
            if (!expected.equals(literal.getDatatypeURI())) {
                return issue(conceptIri, location, "hodnota má datový typ " + terms.compactIri(literal.getDatatypeURI())
                        + ", OFN vyžaduje " + type, object);
            }
            if (expected.equals(OfnIris.XSD + "boolean")) {
                String lexical = literal.getLexicalForm();
                if (lexical.equals("true") || lexical.equals("1")) {
                    return JSON.booleanNode(true);
                }
                if (lexical.equals("false") || lexical.equals("0")) {
                    return JSON.booleanNode(false);
                }
                return issue(conceptIri, location, "neplatná logická hodnota " + lexical, object);
            }
            return JSON.textNode(literal.getLexicalForm());
        }

        private JsonNode nested(Term term, Resource object, Deque<Scope> scopes, String location, String conceptIri) {
            if (!visiting.add(object)) {
                return issue(conceptIri, location, "cyklický odkaz na " + label(object), object);
            }
            try {
                Deque<Scope> nestedScopes = new ArrayDeque<>(scopes);
                nestedScopes.push(term.nested());
                ObjectNode node = JSON.objectNode();
                render(node, object, nestedScopes, location, conceptIri, Level.NESTED);
                return node;
            } finally {
                visiting.remove(object);
            }
        }

        // ------------------------------------------------------------ helpers

        private boolean hasProperties(Resource resource) {
            return model.listStatements(resource, null, (RDFNode) null).hasNext();
        }

        private JsonNode issue(String conceptIri, String location, String message, RDFNode value) {
            String text = value.isLiteral() ? value.asLiteral().getLexicalForm()
                    : value.isURIResource() ? value.asResource().getURI() : null;
            issues.add(new ConformanceIssue(Kind.VALUE_DROPPED, conceptIri, location, message, text));
            return null;
        }

        private String label(RDFNode node) {
            if (node.isURIResource()) {
                return terms.compactIri(node.asResource().getURI());
            }
            if (node.isLiteral()) {
                Literal literal = node.asLiteral();
                String suffix = !literal.getLanguage().isEmpty() ? "@" + literal.getLanguage()
                        : "^^" + terms.compactIri(literal.getDatatypeURI());
                return "\"" + literal.getLexicalForm() + "\"" + suffix;
            }
            return "prázdný uzel";
        }
    }

    private static String conceptLocation(Resource concept) {
        String iri = concept.isURIResource() ? concept.getURI() : "";
        return OfnSpecification.CONCEPTS_KEY + "[" + iri.substring(iri.lastIndexOf('/') + 1) + "]";
    }

    private static String path(String location, String key) {
        return location.isEmpty() ? key : location + "/" + key;
    }

    private static String iri(Resource resource) {
        return resource.isURIResource() ? resource.getURI() : null;
    }
}