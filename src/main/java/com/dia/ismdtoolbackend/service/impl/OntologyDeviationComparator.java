package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.PublishedOntologyDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@Slf4j
public class OntologyDeviationComparator {

    public PublishedOntologyDeviationModel compareOntologyDetails(
            OntologyDetailModel localOntology,
            OntologyDetailModel publishedOntology) {

        if (localOntology == null || publishedOntology == null) {
            log.warn("Cannot compare ontology details: one or both ontologies are null");
            return PublishedOntologyDeviationModel.builder()
                    .status(PublishedConceptDeviationModel.DeviationStatus.QUERY_ERROR)
                    .errorMessage("Local or published ontology detail is null")
                    .build();
        }

        log.debug("Comparing local ontology {} with published version", localOntology.getIri());

        PublishedOntologyDeviationModel.PublishedOntologyDeviationModelBuilder builder =
                PublishedOntologyDeviationModel.builder();

        boolean hasDeviations = false;

        PublishedOntologyDeviationModel.PropertyDeviation<List<String>> typesDeviation =
                compareTypes(localOntology.getTypes(), publishedOntology.getTypes());
        if (typesDeviation.isDifferent()) {
            hasDeviations = true;
            builder.types(typesDeviation);
        }

        PublishedOntologyDeviationModel.PropertyDeviation<Map<String, String>> nameDeviation =
                compareMultilingualProperty(localOntology.getName(), publishedOntology.getName());
        if (nameDeviation.isDifferent()) {
            hasDeviations = true;
            builder.name(nameDeviation);
        }

        PublishedOntologyDeviationModel.PropertyDeviation<Map<String, String>> descriptionDeviation =
                compareMultilingualProperty(localOntology.getDescription(), publishedOntology.getDescription());
        if (descriptionDeviation.isDifferent()) {
            hasDeviations = true;
            builder.description(descriptionDeviation);
        }

        builder.status(hasDeviations
                ? PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS
                : PublishedConceptDeviationModel.DeviationStatus.NO_DEVIATION);

        log.debug("Ontology comparison complete - has deviations: {}", hasDeviations);

        return builder.build();
    }

    private PublishedOntologyDeviationModel.PropertyDeviation<List<String>> compareTypes(
            List<String> localTypes,
            List<String> publishedTypes) {

        boolean different = !Objects.equals(localTypes, publishedTypes);

        return PublishedOntologyDeviationModel.PropertyDeviation.<List<String>>builder()
                .localValue(localTypes)
                .publishedValue(publishedTypes)
                .isDifferent(different)
                .build();
    }

    private PublishedOntologyDeviationModel.PropertyDeviation<Map<String, String>> compareMultilingualProperty(
            Map<String, String> localValue,
            Map<String, String> publishedValue) {

        boolean different = !Objects.equals(localValue, publishedValue);

        return PublishedOntologyDeviationModel.PropertyDeviation.<Map<String, String>>builder()
                .localValue(localValue)
                .publishedValue(publishedValue)
                .isDifferent(different)
                .build();
    }
}