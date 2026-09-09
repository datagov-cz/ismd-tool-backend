package com.dia.ismdtoolbackend.utility.validation;

import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import org.apache.jena.ontology.OntologyException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class ConceptCreateValidator {
    private ConceptCreateValidator() {}

    public static void validate(ConceptCreateModel createModel, String userId) {
        if (createModel == null) {
            throw new OntologyException("Data pro vytvoření pojmu jsou prázdná");
        }

        if (userId == null || userId.trim().isEmpty()) {
            throw new OntologyException("ID uživatele je povinné");
        }

        // name is required and must include a non-blank cs variant
        Map<String, String> name = createModel.getNameModel() != null
                ? createModel.getNameModel().getName() : null;
        if (name == null || name.isEmpty()) {
            throw new ConceptValidationException("Název pojmu je povinný.");
        }
        if (isBlankValue(name.get("cs"))) {
            throw new ConceptValidationException("Název pojmu musí obsahovat českou variantu (cs).");
        }

        // description is optional, but if present it must include a non-blank cs variant
        Map<String, String> description = createModel.getDescriptionModel() != null
                ? createModel.getDescriptionModel().getDescription() : null;
        if (hasAnyValue(description) && isBlankValue(description.get("cs"))) {
            throw new ConceptValidationException("Popis pojmu musí obsahovat českou variantu (cs).");
        }

        // definition is optional, but if present it must include a non-blank cs variant
        Map<String, String> definition = createModel.getDefinitionModel() != null
                ? createModel.getDefinitionModel().getDefinition() : null;
        if (hasAnyValue(definition) && isBlankValue(definition.get("cs"))) {
            throw new ConceptValidationException("Definice pojmu musí obsahovat českou variantu (cs).");
        }

        // Reject the whole create (HTTP 400) if any supplied value is invalid, rather than
        // dropping it silently. Runs the same rule set as the edit path, so identical input
        // fails identically on both verbs.
        List<ConceptInputValidator.InvalidInput> invalid = ConceptInputValidator.validate(createModel);
        if (!invalid.isEmpty()) {
            String detail = invalid.stream()
                    .map(ConceptInputValidator.InvalidInput::toString)
                    .collect(Collectors.joining("; "));
            throw new ConceptValidationException("Neplatné hodnoty při vytváření pojmu: " + detail);
        }
    }

    private static boolean isBlankValue(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean hasAnyValue(Map<String, String> map) {
        return map != null && !map.isEmpty() && map.values().stream().anyMatch(v -> !isBlankValue(v));
    }

}
