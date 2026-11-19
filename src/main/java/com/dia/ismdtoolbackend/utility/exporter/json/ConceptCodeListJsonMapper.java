package com.dia.ismdtoolbackend.utility.exporter.json;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.util.*;

import static com.dia.ismdtoolbackend.constants.VocabularyConstants.*;

/** Maps the code list that defines a concept's instances, with its NKOD dataset. */
final class ConceptCodeListJsonMapper {

    private static final String L111_2009_NAMESPACE = "https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/";

    void addCodeListDatasetProperty(Resource concept, Map<String, Object> conceptObj,
                                              OntModel ontModel) {
        Property instanceDefinedByCodeList = ontModel.getProperty(
                OFN_NAMESPACE + MA_INSTANCE_DEFINOVANE_CISELNIKEM);

        if (!concept.hasProperty(instanceDefinedByCodeList)) {
            return;
        }

        StmtIterator stmtIter = concept.listProperties(instanceDefinedByCodeList);
        while (stmtIter.hasNext()) {
            Statement stmt = stmtIter.next();
            if (!stmt.getObject().isResource()) {
                continue;
            }

            Resource codeListNode = stmt.getObject().asResource();

            Resource codeListType = ontModel.getResource(L111_2009_NAMESPACE + CISELNIK);
            if (!codeListNode.hasProperty(RDF.type, codeListType)) {
                continue;
            }

            Property datasetProperty = ontModel.getProperty(
                    L111_2009_NAMESPACE + MA_V_NKOD_ZASTRESUJICI_DATOVOU_SADU);
            Statement datasetStmt = codeListNode.getProperty(datasetProperty);
            if (datasetStmt != null && datasetStmt.getObject().isResource()) {
                Map<String, Object> codeListObj = new LinkedHashMap<>();
                codeListObj.put(JSON_IRI, codeListNode.getURI());
                codeListObj.put("typ", CISELNIK_JSON_LD);
                codeListObj.put(DATOVA_SADA_V_NKOD, datasetStmt.getObject().asResource().getURI());
                conceptObj.put(INSTANCE_DEFINOVANY_CISELNIKEM, codeListObj);
            }
        }
    }
}
