package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.models.concept.*;
import com.dia.ismdtoolbackend.utility.DataTypeConverter;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.SKOS;

import java.util.List;
import java.util.Map;

import static com.dia.ismdtoolbackend.constants.ExportConstants.Common.DEFAULT_LANG;

/** Writes the preferred label, description, definition and alternative names. */
final class ConceptLabelWriter {

    private final OntModel ontModel;

    ConceptLabelWriter(ConceptBuildContext context) {
        this.ontModel = context.ontModel();
    }

    void addBasicMetadata(Resource resource, ConceptCreateModel model) {
        addPrefLabel(resource, model);
        addDescription(resource, model);
        addDefinition(resource, model);

        if (model.getAltNameModel() != null) {
            addAlternativeNames(resource, model.getAltNameModel());
        }
    }

    void addPrefLabel(Resource resource, ConceptCreateModel model) {
        if (model.getNameModel() != null && model.getNameModel().getName() != null && !model.getNameModel().getName().isEmpty()) {
            for (Map.Entry<String, String> entry : model.getNameModel().getName().entrySet()) {
                if (entry.getValue() != null && !entry.getValue().trim().isEmpty()) {
                    String languageTag = entry.getKey() != null && !entry.getKey().trim().isEmpty()
                            ? entry.getKey()
                            : DEFAULT_LANG;
                    DataTypeConverter.addLangLiteral(resource, SKOS.prefLabel,
                            entry.getValue().trim(), languageTag);
                }
            }
        }
    }

    void addDescription(Resource resource, ConceptCreateModel model) {
        if (model.getDescriptionModel() != null && model.getDescriptionModel().getDescription() != null && !model.getDescriptionModel().getDescription().isEmpty()) {
            Property descProperty = ontModel.createProperty("http://purl.org/dc/terms/description");
            for (Map.Entry<String, String> entry : model.getDescriptionModel().getDescription().entrySet()) {
                if (entry.getValue() != null && !entry.getValue().trim().isEmpty()) {
                    String languageTag = entry.getKey() != null && !entry.getKey().trim().isEmpty()
                            ? entry.getKey()
                            : DEFAULT_LANG;
                    DataTypeConverter.addLangLiteral(resource, descProperty,
                            entry.getValue().trim(), languageTag);
                }
            }
        }
    }

    void addDefinition(Resource resource, ConceptCreateModel model) {
        if (model.getDefinitionModel() != null && model.getDefinitionModel().getDefinition() != null && !model.getDefinitionModel().getDefinition().isEmpty()) {
            for (Map.Entry<String, String> entry : model.getDefinitionModel().getDefinition().entrySet()) {
                if (entry.getValue() != null && !entry.getValue().trim().isEmpty()) {
                    String languageTag = entry.getKey() != null && !entry.getKey().trim().isEmpty()
                            ? entry.getKey()
                            : DEFAULT_LANG;
                    DataTypeConverter.addLangLiteral(resource, SKOS.definition,
                            entry.getValue().trim(), languageTag);
                }
            }
        }
    }

    void addAlternativeNames(Resource resource, AltNameModel altNameModel) {
        if (altNameModel != null && altNameModel.getAltName() != null && !altNameModel.getAltName().isEmpty()) {
            for (Map.Entry<String, List<String>> entry : altNameModel.getAltName().entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                String languageTag = entry.getKey() != null && !entry.getKey().trim().isEmpty()
                    ? entry.getKey()
                    : DEFAULT_LANG;
                for (String value : entry.getValue()) {
                    if (value != null && !value.trim().isEmpty()) {
                        DataTypeConverter.addLangLiteral(resource, SKOS.altLabel,
                            value.trim(), languageTag);
                    }
                }
            }
        }
    }
}
