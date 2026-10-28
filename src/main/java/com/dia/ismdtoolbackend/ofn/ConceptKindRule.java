package com.dia.ismdtoolbackend.ofn;

import com.dia.ismdtoolbackend.ofn.ConformanceIssue.Kind;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;

import java.util.*;

/**
 * ISMD's profile on top of OFN: every concept is a Třída, Vlastnost or Vztah (ISMD writes only the
 * Konceptuální model variant), with exactly one OWL kind, and a role marker, when present, matches that
 * kind. The marker is derived from the OWL kind, never the reverse.
 */
final class ConceptKindRule {

    private ConceptKindRule() {
    }

    static List<ConformanceIssue> check(Model model, Collection<String> conceptIris) {
        List<ConformanceIssue> issues = new ArrayList<>();
        for (String conceptIri : conceptIris) {
            Resource concept = model.createResource(conceptIri);
            Set<String> types = new TreeSet<>();
            for (RDFNode type : model.listObjectsOfProperty(concept, RDF.type).toList()) {
                if (type.isURIResource()) {
                    types.add(type.asResource().getURI());
                }
            }
            Set<String> kinds = new TreeSet<>(types);
            kinds.retainAll(OfnIris.MARKER_BY_OWL_KIND.keySet());
            Set<String> markers = new TreeSet<>(types);
            markers.retainAll(OfnIris.MARKERS);

            String location = OfnSpecification.CONCEPTS_KEY + "[" + OfnIris.localName(conceptIri) + "]/typ";
            if (kinds.isEmpty()) {
                issues.add(new ConformanceIssue(Kind.CONCEPT_EXCLUDED, conceptIri, location,
                        "pojem nemá typ Třída, Vlastnost ani Vztah (owl:Class, owl:DatatypeProperty, owl:ObjectProperty)"));
            } else if (kinds.size() > 1) {
                issues.add(new ConformanceIssue(Kind.CONCEPT_EXCLUDED, conceptIri, location,
                        "pojem má více typů zároveň: " + localNames(kinds)));
            }
            Set<String> expectedMarkers = new TreeSet<>();
            kinds.forEach(kind -> expectedMarkers.add(OfnIris.MARKER_BY_OWL_KIND.get(kind)));
            for (String marker : markers) {
                if (!expectedMarkers.contains(marker)) {
                    issues.add(new ConformanceIssue(Kind.CONCEPT_EXCLUDED, conceptIri, location,
                            "typ slovníky:" + OfnIris.localName(marker) + " neodpovídá OWL typu pojmu "
                                    + (kinds.isEmpty() ? "(žádný)" : localNames(kinds)), marker));
                }
            }
        }
        return issues;
    }

    private static String localNames(Set<String> iris) {
        return String.join(", ", iris.stream().map(iri -> "owl:" + OfnIris.localName(iri)).toList());
    }
}