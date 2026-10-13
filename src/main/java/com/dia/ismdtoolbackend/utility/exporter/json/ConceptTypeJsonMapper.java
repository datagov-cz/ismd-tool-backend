package com.dia.ismdtoolbackend.utility.exporter.json;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.util.*;

import static com.dia.constants.VocabularyConstants.*;

/** Decides which resources are concepts and maps their RDF types to JSON type names. */
final class ConceptTypeJsonMapper {

    Set<Resource> getConceptTypes(OntModel ontModel) {
        return Set.of(
                ontModel.getResource(OFN_NAMESPACE + POJEM),
                ontModel.getResource(OFN_NAMESPACE + VZTAH),
                ontModel.getResource(OFN_NAMESPACE + VLASTNOST),
                ontModel.getResource(OFN_NAMESPACE + TRIDA),
                ontModel.getResource(OFN_NAMESPACE + TSP),
                ontModel.getResource(OFN_NAMESPACE + TOP),
                ontModel.getResource(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ),
                ontModel.getResource(OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ),
                ontModel.createResource(SKOS_NS + "Concept"),
                ontModel.createResource("http://www.w3.org/2002/07/owl#Class"),
                ontModel.createResource("http://www.w3.org/2002/07/owl#ObjectProperty"),
                ontModel.createResource("http://www.w3.org/2002/07/owl#DatatypeProperty")
        );
    }

    boolean isConceptResource(Resource resource, Set<Resource> conceptTypes) {
        return conceptTypes.stream().anyMatch(type -> resource.hasProperty(RDF.type, type));
    }

    List<String> getConceptTypes(Resource concept, OntModel ontModel) {
        List<String> types = new ArrayList<>();
        types.add(POJEM_JSON_LD);
        types.add("Koncept");

        String[][] typeMapping = {
                {TRIDA, TRIDA_JSON_LD},
                {VZTAH, VZTAH_JSON_LD},
                {VLASTNOST, VLASTNOST_JSON_LD},
                {TSP, TSP_JSON_LD},
                {TOP, TOP_JSON_LD},
                {VEREJNY_UDAJ, VEREJNY_UDAJ_JSON_LD},
                {NEVEREJNY_UDAJ, NEVEREJNY_UDAJ_JSON_LD}
        };

        boolean isVztah = concept.hasProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + VZTAH));

        for (String[] mapping : typeMapping) {
            String namespace = (mapping[0].equals(VEREJNY_UDAJ) || mapping[0].equals(NEVEREJNY_UDAJ))
                    ? OFN_NAMESPACE_LEGAL : OFN_NAMESPACE;
            if (concept.hasProperty(RDF.type, ontModel.getResource(namespace + mapping[0]))) {
                if (mapping[0].equals(VLASTNOST) && isVztah) {
                    continue;
                }
                types.add(mapping[1]);
            }
        }

        addOwlTypeIfPresent(concept, ontModel, types, isVztah);

        return types;
    }

    void addOwlTypeIfPresent(Resource concept, OntModel ontModel, List<String> types, boolean isVztah) {
        if (concept.hasProperty(RDF.type, ontModel.getResource("http://www.w3.org/2002/07/owl#Class")) && !types.contains(TRIDA_JSON_LD)) {
            types.add(TRIDA_JSON_LD);
        }

        if (concept.hasProperty(RDF.type, ontModel.getResource("http://www.w3.org/2002/07/owl#ObjectProperty")) && !types.contains(VZTAH_JSON_LD)) {
            types.add(VZTAH_JSON_LD);
        }

        if (concept.hasProperty(RDF.type, ontModel.getResource("http://www.w3.org/2002/07/owl#DatatypeProperty")) && !isVztah && !types.contains(VLASTNOST_JSON_LD)) {
            types.add(VLASTNOST_JSON_LD);
        }
    }
}
