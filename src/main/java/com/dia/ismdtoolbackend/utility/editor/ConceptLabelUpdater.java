package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.concept.AltNameModel;
import com.dia.ismdtoolbackend.models.concept.DefinitionModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.SKOS;

import java.util.*;

import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;
import static com.dia.ismdtoolbackend.utility.editor.RdfLangValues.removeAllByPredicate;

/** Edits the name, description, definition and alternative names of a concept. */
class ConceptLabelUpdater {

    void updateNameModel(Resource newConcept, NameModel nameModel, Resource oldConcept,
                         Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (nameModel == null || nameModel.getName() == null) return;

        // Name is required: an empty incoming map is a no-op (never a clear-all),
        // which the merge produces naturally (merged == existing).
        Map<String, String> existing = RdfLangValues.byLanguage(oldConcept, SKOS.prefLabel);
        applyMergedLangProperty(newConcept, SKOS.prefLabel, existing, nameModel.getName(),
                model, toRemove, toAdd);
    }

    void updateDescriptionModel(Resource newConcept, DescriptionModel descModel, Resource oldConcept,
                                Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (descModel == null) return;

        Property descProperty = model.createProperty("http://purl.org/dc/terms/description");
        Map<String, String> existing = RdfLangValues.byLanguage(oldConcept, descProperty);
        Map<String, String> incoming = descModel.getDescription();

        // An empty/absent incoming map clears the field entirely.
        if (incoming == null || incoming.isEmpty()) {
            if (!existing.isEmpty()) {
                removeAllByPredicate(newConcept, descProperty, toRemove, toAdd);
            }
            return;
        }

        applyMergedLangProperty(newConcept, descProperty, existing, incoming, model, toRemove, toAdd);
    }

    void updateDefinitionModel(Resource newConcept, DefinitionModel defModel, Resource oldConcept,
                                Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (defModel == null) return;

        Map<String, String> existing = RdfLangValues.byLanguage(oldConcept, SKOS.definition);
        Map<String, String> incoming = defModel.getDefinition();

        // An empty/absent incoming map clears the field entirely.
        if (incoming == null || incoming.isEmpty()) {
            if (!existing.isEmpty()) {
                removeAllByPredicate(newConcept, SKOS.definition, toRemove, toAdd);
            }
            return;
        }

        applyMergedLangProperty(newConcept, SKOS.definition, existing, incoming, model, toRemove, toAdd);
    }

    void updateAltNameModel(Resource newConcept, AltNameModel altNameModel, Resource oldConcept,
                             Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (altNameModel == null) return;

        Map<String, List<String>> oldAltNamesByLang = RdfLangValues.allByLanguage(oldConcept);

        Map<String, List<String>> newAltNamesByLang = new HashMap<>();
        if (altNameModel.getAltName() != null && !altNameModel.getAltName().isEmpty()) {
            for (Map.Entry<String, List<String>> entry : altNameModel.getAltName().entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                String languageTag = entry.getKey() != null && !entry.getKey().trim().isEmpty()
                    ? entry.getKey()
                    : DEFAULT_LANG;
                for (String value : entry.getValue()) {
                    if (value != null && !value.trim().isEmpty()) {
                        newAltNamesByLang.computeIfAbsent(languageTag, k -> new ArrayList<>())
                                .add(value.trim());
                    }
                }
            }
            // Sorted to match allByLanguage's ordering, so the comparison below is order-insensitive.
            newAltNamesByLang.values().forEach(Collections::sort);
        }

        if (!oldAltNamesByLang.equals(newAltNamesByLang)) {
            removeAllByPredicate(newConcept, SKOS.altLabel, toRemove, toAdd);
            for (Map.Entry<String, List<String>> entry : newAltNamesByLang.entrySet()) {
                for (String value : entry.getValue()) {
                    toAdd.add(model.createStatement(newConcept, SKOS.altLabel,
                            model.createLiteral(value, entry.getKey())));
                }
            }
        }
    }

    /**
     * Merges an incoming {@code lang -> value} map into the existing values of a
     * language-tagged literal property and, if the result differs, rewrites the
     * whole property. This is the shared MERGE semantics for prefLabel /
     * description / definition (an edit touching one language preserves the
     * others). A blank/empty incoming value deletes that language tag; a blank
     * tag falls back to {@link com.dia.constants.ExportConstants.Common#DEFAULT_LANG}.
     *
     * <p>NOTE: callers decide what an empty/absent incoming map means. Name is
     * required and never cleared; description/definition clear-all on an empty
     * map before calling this. altLabel is intentionally NOT routed here — it is
     * full-replace, not merge.
     */
    private void applyMergedLangProperty(Resource newConcept, Property property,
                                         Map<String, String> existing, Map<String, String> incoming,
                                         Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        Map<String, String> merged = new HashMap<>(existing);
        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            if (entry.getValue() == null || entry.getValue().trim().isEmpty()) {
                merged.remove(entry.getKey());
            } else {
                merged.put(entry.getKey(), entry.getValue().trim());
            }
        }

        if (existing.equals(merged)) return;

        removeAllByPredicate(newConcept, property, toRemove, toAdd);
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            String languageTag = entry.getKey() != null && !entry.getKey().trim().isEmpty()
                    ? entry.getKey()
                    : DEFAULT_LANG;
            toAdd.add(model.createStatement(newConcept, property,
                    model.createLiteral(entry.getValue(), languageTag)));
        }
    }
}
