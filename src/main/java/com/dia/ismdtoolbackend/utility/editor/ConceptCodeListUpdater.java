package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.utility.sparql.SparqlSolutions;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.util.Set;

import static com.dia.constants.VocabularyConstants.*;

/** Edits the code list a class concept takes its instances from. */
class ConceptCodeListUpdater {

    /**
     * Rewrites the code-list structure: the číselník is a named subject carrying its type and
     * its NKOD dataset. Class concepts only.
     *
     * <p>The removal branch drops the old číselník's own statements as well as the link. Fully exhaustive.
     */
    void updateCodeListDataset(Resource newConcept, String newCodeListIri, String newDatasetUrl,
                                         Resource oldConcept, Model model,
                                         Set<Statement> toRemove, Set<Statement> toAdd) {
        if (newCodeListIri == null && newDatasetUrl == null) return;

        Property instanceDefinedByCodeList = model.createProperty(
                OFN_NAMESPACE + MA_INSTANCE_DEFINOVANE_CISELNIKEM);

        if (oldConcept.hasProperty(instanceDefinedByCodeList)) {
            StmtIterator stmtIter = oldConcept.listProperties(instanceDefinedByCodeList);
            while (stmtIter.hasNext()) {
                Statement stmt = stmtIter.next();
                toRemove.add(stmt);
                if (stmt.getObject().isResource()) {
                    Resource codeListNode = stmt.getObject().asResource();
                    StmtIterator nodeIter = codeListNode.listProperties();
                    while (nodeIter.hasNext()) {
                        toRemove.add(nodeIter.next());
                    }
                }
            }
        }

        // Add the new structure. Both IRIs are present together or not at all —
        // ConceptInputValidator rejects the one-sided cases before this runs.
        if (newCodeListIri != null && !newCodeListIri.trim().isEmpty()
                && newDatasetUrl != null && !newDatasetUrl.trim().isEmpty()) {
            Resource codeListType = model.createResource(
                    OFN_NAMESPACE_LEGAL + CISELNIK);
            Property datasetProperty = model.createProperty(
                    OFN_NAMESPACE_LEGAL + MA_V_NKOD_ZASTRESUJICI_DATOVOU_SADU);

            Resource codeListNode = model.createResource(newCodeListIri.trim());
            toAdd.add(model.createStatement(codeListNode, RDF.type, codeListType));
            toAdd.add(model.createStatement(codeListNode, datasetProperty,
                    model.createResource(SparqlSolutions.toRawUtf8(newDatasetUrl.trim()))));
            toAdd.add(model.createStatement(newConcept, instanceDefinedByCodeList, codeListNode));
        }
    }
}
