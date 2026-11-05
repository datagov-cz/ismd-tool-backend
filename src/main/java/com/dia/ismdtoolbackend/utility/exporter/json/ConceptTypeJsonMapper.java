package com.dia.ismdtoolbackend.utility.exporter.json;

import com.dia.ismdtoolbackend.exception.ConflictingConceptTypeException;
import com.dia.ismdtoolbackend.utility.validation.ConceptRoleConflict;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.util.*;

import static com.dia.ismdtoolbackend.constants.VocabularyConstants.*;

/** Decides which resources are concepts and maps their RDF types to JSON type names. */
final class ConceptTypeJsonMapper {

    private static final String OWL_CLASS = "http://www.w3.org/2002/07/owl#Class";
    private static final String OWL_OBJECT_PROPERTY = "http://www.w3.org/2002/07/owl#ObjectProperty";
    private static final String OWL_DATATYPE_PROPERTY = "http://www.w3.org/2002/07/owl#DatatypeProperty";

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
                ontModel.createResource(OWL_CLASS),
                ontModel.createResource(OWL_OBJECT_PROPERTY),
                ontModel.createResource(OWL_DATATYPE_PROPERTY)
        );
    }

    boolean isConceptResource(Resource resource, Set<Resource> conceptTypes) {
        return conceptTypes.stream().anyMatch(type -> resource.hasProperty(RDF.type, type));
    }

    /**
     * Maps a concept's RDF types to JSON type names. Property and relationship are exclusive: the OFN
     * tag and the OWL type must agree, and a concept carrying one of each kind is rejected.
     */
    List<String> getConceptTypes(Resource concept, OntModel ontModel) {
        if (ConceptRoleConflict.isPropertyAndRelationship(concept)) {
            throw new ConflictingConceptTypeException(concept.getURI());
        }

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

        for (String[] mapping : typeMapping) {
            String namespace = (mapping[0].equals(VEREJNY_UDAJ) || mapping[0].equals(NEVEREJNY_UDAJ))
                    ? OFN_NAMESPACE_LEGAL : OFN_NAMESPACE;
            if (hasType(concept, ontModel, namespace + mapping[0])) {
                types.add(mapping[1]);
            }
        }

        addOwlTypeIfPresent(concept, ontModel, types);

        return types;
    }

    void addOwlTypeIfPresent(Resource concept, OntModel ontModel, List<String> types) {
        if (hasType(concept, ontModel, OWL_CLASS) && !types.contains(TRIDA_JSON_LD)) {
            types.add(TRIDA_JSON_LD);
        }

        if (hasType(concept, ontModel, OWL_OBJECT_PROPERTY) && !types.contains(VZTAH_JSON_LD)) {
            types.add(VZTAH_JSON_LD);
        }

        if (hasType(concept, ontModel, OWL_DATATYPE_PROPERTY) && !types.contains(VLASTNOST_JSON_LD)) {
            types.add(VLASTNOST_JSON_LD);
        }
    }

    private static boolean hasType(Resource concept, OntModel ontModel, String typeIri) {
        return concept.hasProperty(RDF.type, ontModel.getResource(typeIri));
    }
}
