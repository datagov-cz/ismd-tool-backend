package com.dia.ismdtoolbackend.utility.exporter.json;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;

import java.util.*;

import static com.dia.ismdtoolbackend.constants.VocabularyConstants.*;

/** Maps the privacy provisions of a non-public concept. */
final class ConceptPrivacyJsonMapper {

    private static final String L111_2009_NAMESPACE = "https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/";

    /**
     * Reads the privacy provisions under both property IRIs they are stored as: the one the
     * writers ({@code ConceptCreator} / {@code ConceptFieldUpdaters}) use, and the canonical OFN
     * one that {@code TurtleFormatterUtil} rewrites it to and published vocabularies carry.
     */
    void addUstanoveniProperty(Resource concept, Map<String, Object> conceptObj,
                                       OntModel ontModel) {
        Set<String> provisions = new LinkedHashSet<>();
        for (String localName : List.of(USTANOVENI_NEVEREJNOST, USTANOVENI_LONG)) {
            Map<String, Object> read = new HashMap<>();
            RdfJsonValues.addResourceArrayProperty(concept, ontModel.createProperty(L111_2009_NAMESPACE + localName),
                    USTANOVENI_NEVEREJNOST, read);
            @SuppressWarnings("unchecked")
            List<String> values = (List<String>) read.get(USTANOVENI_NEVEREJNOST);
            if (values != null) provisions.addAll(values);
        }
        if (!provisions.isEmpty()) {
            conceptObj.put(USTANOVENI_NEVEREJNOST, new ArrayList<>(provisions));
        }
    }
}
