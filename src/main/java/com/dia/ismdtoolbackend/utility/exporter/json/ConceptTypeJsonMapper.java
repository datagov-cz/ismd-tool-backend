package com.dia.ismdtoolbackend.utility.exporter.json;

import com.dia.ismdtoolbackend.exception.ConflictingConceptTypeException;
import com.dia.ismdtoolbackend.ofn.OfnIris;
import com.dia.ismdtoolbackend.utility.validation.ConceptRoleConflict;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.util.*;

import static com.dia.constants.VocabularyConstants.*;

/** Decides which resources are concepts and maps their RDF types to JSON type names. */
final class ConceptTypeJsonMapper {

    private static final String OWL_CLASS = "http://www.w3.org/2002/07/owl#Class";
    private static final String OWL_OBJECT_PROPERTY = "http://www.w3.org/2002/07/owl#ObjectProperty";
    private static final String OWL_DATATYPE_PROPERTY = "http://www.w3.org/2002/07/owl#DatatypeProperty";

    Set<Resource> getConceptTypes(OntModel ontModel) {
        return Set.of(
                ontModel.getResource(OFN_NAMESPACE + POJEM),
                ontModel.getResource(OFN_NAMESPACE + TSP),
                ontModel.getResource(OFN_NAMESPACE + TOP),
                ontModel.getResource(OfnIris.TYP_SUBJEKTU_PRAVA),
                ontModel.getResource(OfnIris.TYP_OBJEKTU_PRAVA),
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
     * Maps a concept's RDF types to JSON type names; the kind comes from the OWL type (the ISMD role
     * marker is derived from it and not read). A concept typed as both property and relationship is
     * rejected.
     */
    List<String> getConceptTypes(Resource concept, OntModel ontModel) {
        if (ConceptRoleConflict.isPropertyAndRelationship(concept)) {
            throw new ConflictingConceptTypeException(concept.getURI());
        }

        List<String> types = new ArrayList<>();
        types.add(POJEM_JSON_LD);
        types.add("Koncept");

        // Order is part of the payload: kind first, then the legal and publicity types.
        String[][] typeMapping = {
                {OWL_CLASS, TRIDA_JSON_LD},
                {OWL_OBJECT_PROPERTY, VZTAH_JSON_LD},
                {OWL_DATATYPE_PROPERTY, VLASTNOST_JSON_LD},
                {OFN_NAMESPACE + TSP, TSP_JSON_LD},
                {OFN_NAMESPACE + TOP, TOP_JSON_LD},
                {OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ, VEREJNY_UDAJ_JSON_LD},
                {OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ, NEVEREJNY_UDAJ_JSON_LD}
        };

        for (String[] mapping : typeMapping) {
            if (hasType(concept, ontModel, mapping[0])) {
                types.add(mapping[1]);
            }
        }
        // Typ subjektu/objektu práva: the OFN vsgov: type (the slovníky: one above is the legacy shape).
        if (hasType(concept, ontModel, OfnIris.TYP_SUBJEKTU_PRAVA) && !types.contains(TSP_JSON_LD)) {
            types.add(TSP_JSON_LD);
        }
        if (hasType(concept, ontModel, OfnIris.TYP_OBJEKTU_PRAVA) && !types.contains(TOP_JSON_LD)) {
            types.add(TOP_JSON_LD);
        }

        return types;
    }

    private static boolean hasType(Resource concept, OntModel ontModel, String typeIri) {
        return concept.hasProperty(RDF.type, ontModel.getResource(typeIri));
    }
}
