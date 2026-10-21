package com.dia.ismdtoolbackend.testsupport.fixtures;

import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;

import java.util.Set;
import java.util.stream.Collectors;

import static com.dia.constants.VocabularyConstants.*;

/**
 * Turtle seeding and triple reads for RDF assertions.
 *
 * <p>{@link #model(String)} parses Turtle with the project prefixes declared:
 * {@code ofn:} (OFN generic), {@code l111:} (111/2009), {@code a104:} (agendový 104),
 * {@code pojem:} (concepts in the default namespace), {@code sl:} (the default namespace) and the
 * usual {@code rdf: rdfs: owl: skos: xsd: dct: schema:}.
 */
public final class Rdf {

    public static final String PREFIXES = """
            @prefix rdf:    <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs:   <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl:    <http://www.w3.org/2002/07/owl#> .
            @prefix skos:   <http://www.w3.org/2004/02/skos/core#> .
            @prefix xsd:    <http://www.w3.org/2001/XMLSchema#> .
            @prefix dct:    <http://purl.org/dc/terms/> .
            @prefix schema: <http://schema.org/> .
            @prefix ofn:    <%s> .
            @prefix l111:   <%s> .
            @prefix a104:   <%s> .
            @prefix sl:     <%s> .
            @prefix pojem:  <%s> .
            """.formatted(OFN_NAMESPACE, OFN_NAMESPACE_LEGAL, DEFAULT_NS + AGENDOVY_104, DEFAULT_NS,
            DEFAULT_NS + "pojem/");

    private Rdf() {
    }

    /** Parses Turtle, with {@link #PREFIXES} declared, into a new model. */
    public static Model model(String turtle) {
        Model model = ModelFactory.createDefaultModel();
        RDFParser.fromString(PREFIXES + turtle, Lang.TURTLE).parse(model);
        return model;
    }

    /** Adds the parsed Turtle to {@code target}. */
    public static void add(Model target, String turtle) {
        target.add(model(turtle));
    }

    public static Property property(String iri) {
        return ResourceFactory.createProperty(iri);
    }

    /** The IRIs that {@code subject} points to through {@code property}. */
    public static Set<String> iris(Resource subject, Property property) {
        return subject.listProperties(property).toList().stream()
                .map(statement -> statement.getObject())
                .filter(RDFNode::isURIResource)
                .map(node -> node.asResource().getURI())
                .collect(Collectors.toSet());
    }

    public static Set<String> iris(Model model, String subject, Property property) {
        return iris(model.getResource(subject), property);
    }

    /** The {@code rdf:type} IRIs of {@code subject}. */
    public static Set<String> types(Model model, String subject) {
        return iris(model, subject, org.apache.jena.vocabulary.RDF.type);
    }

    /** The literals of {@code subject} through {@code property}, each as {@code "value@lang"} (or {@code "value"}). */
    public static Set<String> literals(Model model, String subject, Property property) {
        return model.getResource(subject).listProperties(property).toList().stream()
                .map(statement -> statement.getObject())
                .filter(RDFNode::isLiteral)
                .map(node -> {
                    Literal literal = node.asLiteral();
                    return literal.getLanguage().isEmpty()
                            ? literal.getLexicalForm()
                            : literal.getLexicalForm() + "@" + literal.getLanguage();
                })
                .collect(Collectors.toSet());
    }
}
