package com.dia.ismdtoolbackend.ofn;

import com.dia.ismdtoolbackend.ofn.ConformanceIssue.Kind;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;

import java.util.*;

/**
 * ISMD's profile on top of OFN: every concept is a Třída, Vlastnost or Vztah (ISMD writes only the
 * Konceptuální model variant), with exactly one OWL kind.
 */
final class ConceptKindRule {

    private ConceptKindRule() {
    }

    static List<ConformanceIssue> check(Model model, Collection<String> conceptIris) {
        List<ConformanceIssue> issues = new ArrayList<>();
        for (String conceptIri : conceptIris) {
            Resource concept = model.createResource(conceptIri);
            Set<String> kinds = new TreeSet<>();
            for (RDFNode type : model.listObjectsOfProperty(concept, RDF.type).toList()) {
                if (type.isURIResource() && OfnIris.OWL_KINDS.contains(type.asResource().getURI())) {
                    kinds.add(type.asResource().getURI());
                }
            }

            String location = OfnSpecification.CONCEPTS_KEY + "[" + OfnIris.localName(conceptIri) + "]/typ";
            if (kinds.isEmpty()) {
                issues.add(new ConformanceIssue(Kind.CONCEPT_EXCLUDED, conceptIri, location,
                        "pojem nemá typ Třída, Vlastnost ani Vztah (owl:Class, owl:DatatypeProperty, owl:ObjectProperty)"));
            } else if (kinds.size() > 1) {
                issues.add(new ConformanceIssue(Kind.CONCEPT_EXCLUDED, conceptIri, location,
                        "pojem má více typů zároveň: " + localNames(kinds)));
            }
        }
        return issues;
    }

    private static String localNames(Set<String> iris) {
        return String.join(", ", iris.stream().map(iri -> "owl:" + OfnIris.localName(iri)).toList());
    }
}