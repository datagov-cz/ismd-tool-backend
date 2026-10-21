package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.models.concept.*;
import org.apache.jena.rdf.model.Resource;

import java.util.Map;

import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;

/** The characteristic writers of one concept build, shared by the three concept-type writers. */
record ConceptCharacteristicWriters(ConceptBuildContext context,
                                    ConceptLabelWriter labels,
                                    ConceptSourceWriter sources,
                                    ConceptRelationWriter relations,
                                    ConceptGovernanceWriter governance,
                                    ConceptDataClassificationWriter classification,
                                    ConceptCodeListWriter codeLists) {

    static ConceptCharacteristicWriters forContext(ConceptBuildContext context) {
        return new ConceptCharacteristicWriters(context,
                new ConceptLabelWriter(context),
                new ConceptSourceWriter(context),
                new ConceptRelationWriter(context),
                new ConceptGovernanceWriter(context),
                new ConceptDataClassificationWriter(context),
                new ConceptCodeListWriter(context));
    }

    void addCommonMetadata(Resource resource, ConceptCreateModel model) {
        labels.addBasicMetadata(resource, model);
        sources.addSourceMetadata(resource, model);
        relations.addMatchMetadata(resource, model);
        relations.addInScheme(resource, model);
    }

    String getNameForUriGeneration(com.dia.ismdtoolbackend.models.NameModel nameModel) {
        if (nameModel == null || nameModel.getName() == null || nameModel.getName().isEmpty()) {
            return "";
        }
        Map<String, String> names = nameModel.getName();
        if (names.containsKey(DEFAULT_LANG)) {
            return names.get(DEFAULT_LANG);
        }
        return names.values().iterator().next();
    }
}
