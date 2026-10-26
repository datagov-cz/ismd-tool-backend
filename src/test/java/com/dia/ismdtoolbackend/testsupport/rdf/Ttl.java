package com.dia.ismdtoolbackend.testsupport.rdf;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.vocabulary.RDF;

import java.io.StringWriter;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turtle text to Jena models, with the project prefixes predeclared so fixtures read like the
 * data they stand for.
 *
 * <pre>{@code
 * Model m = Ttl.parse("""
 *     pojem:osoba a owl:Class, slovníky:třída ;
 *         skos:prefLabel "Osoba"@cs .
 *     """);
 * }</pre>
 *
 * <p>{@code ofn:} is an alias of {@code slovníky:} and {@code sl:} is the {@code https://slovník.gov.cz/}
 * root, matching {@code VocabularyConstants.OFN_NAMESPACE} and {@code DEFAULT_NS}.
 */
public final class Ttl {

    /** Vocabulary IRI of the test vocabulary ({@code ex:}). */
    public static final String VOCABULARY = "https://slovník.gov.cz/datový/test-slovník/";
    /** Concept namespace of the test vocabulary ({@code pojem:}). */
    public static final String POJEM = VOCABULARY + "pojem/";

    public static final String PREFIXES = """
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
            @prefix dct: <http://purl.org/dc/terms/> .
            @prefix schema: <http://schema.org/> .
            @prefix slovníky: <https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/> .
            @prefix vsgov: <https://slovník.gov.cz/veřejný-sektor/pojem/> .
            @prefix l111: <https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/> .
            @prefix a104: <https://slovník.gov.cz/agendový/104/pojem/> .
            @prefix čas: <https://slovník.gov.cz/generický/čas/pojem/> .
            @prefix do: <https://slovník.gov.cz/generický/digitální-objekty/pojem/> .
            @prefix ex: <https://slovník.gov.cz/datový/test-slovník/> .
            @prefix pojem: <https://slovník.gov.cz/datový/test-slovník/pojem/> .
            @prefix ofn: <https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/> .
            @prefix sl: <https://slovník.gov.cz/> .
            """;

    static {
        JenaSystem.init();
    }

    private Ttl() {}

    /**
     * Initializes Jena. A class whose static fields read {@code RDF}/{@code RDFS}/{@code OWL2}
     * fails with {@code ExceptionInInitializerError} when it is the first Jena class a JVM loads;
     * a test that may touch such a class first calls this.
     */
    public static void initJena() {
        JenaSystem.init();
    }

    /** Parses Turtle with {@link #PREFIXES} predeclared. */
    public static Model parse(String turtle) {
        Model model = ModelFactory.createDefaultModel();
        RDFParser.fromString(PREFIXES + turtle, Lang.TURTLE).parse(model);
        return model;
    }

    /** Adds the parsed Turtle to {@code target}. */
    public static void add(Model target, String turtle) {
        target.add(parse(turtle));
    }

    /** Parses Turtle into an {@code OWL_MEM} ontology model, as the exporters receive it. */
    public static OntModel parseOnt(String turtle) {
        OntModel model = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);
        model.add(parse(turtle));
        model.setNsPrefixes(parse("").getNsPrefixMap());
        return model;
    }

    /** Serializes a model as Turtle. */
    public static String write(Model model) {
        StringWriter out = new StringWriter();
        RDFDataMgr.write(out, model, Lang.TURTLE);
        return out.toString();
    }

    /** Serializes and re-parses a model, as a client reading the exported file would. */
    public static Model roundTrip(Model model) {
        Model reparsed = ModelFactory.createDefaultModel();
        RDFParser.fromString(write(model), Lang.TURTLE).parse(reparsed);
        return reparsed;
    }

    public static Property property(String iri) {
        return ResourceFactory.createProperty(iri);
    }

    /** The IRIs that {@code subject} points to through {@code property}. */
    public static Set<String> iris(Resource subject, Property property) {
        return subject.listProperties(property).toList().stream()
                .map(Statement::getObject)
                .filter(RDFNode::isURIResource)
                .map(node -> node.asResource().getURI())
                .collect(Collectors.toSet());
    }

    public static Set<String> iris(Model model, String subject, Property property) {
        return iris(model.getResource(subject), property);
    }

    /** The {@code rdf:type} IRIs of {@code subject}. */
    public static Set<String> types(Model model, String subject) {
        return iris(model, subject, RDF.type);
    }

    /** The literals of {@code subject} through {@code property}, each as {@code "value@lang"} (or {@code "value"}). */
    public static Set<String> literals(Model model, String subject, Property property) {
        return model.getResource(subject).listProperties(property).toList().stream()
                .map(Statement::getObject)
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
