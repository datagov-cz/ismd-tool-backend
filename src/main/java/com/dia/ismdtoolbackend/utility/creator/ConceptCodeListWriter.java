package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.models.concept.*;
import com.dia.ismdtoolbackend.utility.sparql.SparqlSolutions;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import static com.dia.constants.VocabularyConstants.*;

/** Writes the code list that defines a concept's instances, with its NKOD dataset. */
final class ConceptCodeListWriter {

    private final OntModel ontModel;

    ConceptCodeListWriter(ConceptBuildContext context) {
        this.ontModel = context.ontModel();
    }

    /**
     * Writes the code-list structure: the concept links to the číselník, which is a named
     * subject carrying its type and its NKOD dataset. Both IRIs are mandatory together.
     */
    void addCodeListDataset(Resource resource, String codeListIri, String datasetUrl) {
        boolean hasIri = codeListIri != null && !codeListIri.trim().isEmpty();
        boolean hasDataset = datasetUrl != null && !datasetUrl.trim().isEmpty();

        if (!hasIri && !hasDataset) return;

        ConceptValidationUtil.validateCodeListIri(codeListIri);
        ConceptValidationUtil.validateCodeListDataset(datasetUrl);
        ConceptValidationUtil.validateCodeListCompleteness(codeListIri, datasetUrl);

        if (!hasIri || !hasDataset) return;

        String iri = codeListIri.trim();
        String dataset = SparqlSolutions.toRawUtf8(datasetUrl.trim());

        Property instanceDefinedByCodeList = ontModel.createProperty(
                OFN_NAMESPACE + MA_INSTANCE_DEFINOVANE_CISELNIKEM);
        Resource codeListType = ontModel.createResource(
                OFN_NAMESPACE_LEGAL + CISELNIK);
        Property datasetProperty = ontModel.createProperty(
                OFN_NAMESPACE_LEGAL + MA_V_NKOD_ZASTRESUJICI_DATOVOU_SADU);

        Resource codeListNode = ontModel.createResource(iri);
        codeListNode.addProperty(RDF.type, codeListType);
        codeListNode.addProperty(datasetProperty, ontModel.createResource(dataset));

        resource.addProperty(instanceDefinedByCodeList, codeListNode);
    }
}
