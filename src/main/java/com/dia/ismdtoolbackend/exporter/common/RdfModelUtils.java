package com.dia.ismdtoolbackend.exporter.common;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.*;

public class RdfModelUtils {

    private static final String CONCEPT_LITERAL = "Concept";
    private static final String POJEM_SUFFIX = "/pojem/";

    private static final Map<String, Property> PROPERTY_CACHE = new HashMap<>();

    private RdfModelUtils() {}

    public static List<Resource> getResourcesByType(Model model, Resource type) {
        List<Resource> resources = new ArrayList<>();
        StmtIterator iter = model.listStatements(null, RDF.type, type);
        while (iter.hasNext()) {
            resources.add(iter.next().getSubject());
        }
        return resources;
    }

    public static List<Resource> getResourcesByTypeURI(Model model, String typeURI) {
        Resource type = model.getResource(typeURI);
        return getResourcesByType(model, type);
    }

    public static boolean hasConceptOfType(Model model, String conceptType) {
        Resource typeResource = model.getResource(OFN_NAMESPACE + conceptType);
        StmtIterator iter = model.listStatements(null, RDF.type, typeResource);
        return iter.hasNext();
    }

    public static Property getOrCreateProperty(OntModel model, String namespace, String propertyName) {
        String fullURI = namespace + propertyName;
        return PROPERTY_CACHE.computeIfAbsent(fullURI, uri -> model.getProperty(uri));
    }

    public static List<Resource> getOWLClasses(Model model) {
        return getResourcesByType(model, OWL2.Class);
    }

    public static List<Resource> getOWLDatatypeProperties(Model model) {
        return getResourcesByType(model, OWL2.DatatypeProperty);
    }

    public static List<Resource> getOWLObjectProperties(Model model) {
        return getResourcesByType(model, OWL2.ObjectProperty);
    }

    public static List<Resource> getAllOWLProperties(Model model) {
        List<Resource> properties = new ArrayList<>();
        properties.addAll(getOWLDatatypeProperties(model));
        properties.addAll(getOWLObjectProperties(model));
        return properties;
    }

    public static List<Resource> getConceptResources(Model model) {
        List<Resource> concepts = new ArrayList<>();

        String[] conceptTypes = {POJEM, TRIDA, VZTAH, VLASTNOST, TSP, TOP, VEREJNY_UDAJ, NEVEREJNY_UDAJ};
        for (String conceptType : conceptTypes) {
            concepts.addAll(getResourcesByTypeURI(model, OFN_NAMESPACE + conceptType));
        }

        Property skosConceptProperty = model.createProperty(SKOS_NS + CONCEPT_LITERAL);
        concepts.addAll(getResourcesByType(model, skosConceptProperty));

        return concepts;
    }

    public static boolean isConceptResource(Resource resource) {
        return resource.getURI() != null &&
               resource.getURI().contains(POJEM_SUFFIX) &&
               !isBaseVocabularyClass(resource.getURI());
    }

    public static boolean isBaseVocabularyClass(String uri) {
        if (uri == null) return false;

        return uri.startsWith("http://www.w3.org/") ||
               uri.startsWith("https://slovník.gov.cz/veřejný-sektor/pojem/typ-") ||
               uri.contains("/generický/") ||
               uri.contains("cz:třída") ||
               uri.contains("cz:pojem");
    }

    public static String extractOntologyIRI(String resourceURI) {
        if (resourceURI != null && resourceURI.contains(POJEM_SUFFIX)) {
            return resourceURI.substring(0, resourceURI.lastIndexOf(POJEM_SUFFIX));
        }
        return null;
    }

    public static void clearPropertyCache() {
        PROPERTY_CACHE.clear();
    }
}