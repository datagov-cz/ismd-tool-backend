package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.DigitalObjectModel;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaEliParser;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import com.dia.ismdtoolbackend.utility.UtilityMethods;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.dia.ismdtoolbackend.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.constants.ExportConstants.Common.DEFAULT_LANG;
import static com.dia.ismdtoolbackend.utility.editor.ConceptEditStatements.*;
import static com.dia.ismdtoolbackend.utility.editor.RdfLangValues.removeAllByPredicate;

/** Edits the legal and non-legal sources of a concept. */
@Slf4j
class ConceptSourceUpdater {

    private final ConceptIriFactory iriFactory;

    ConceptSourceUpdater(ConceptIriFactory iriFactory) {
        this.iriFactory = iriFactory;
    }

    void updateLegalSources(Resource newConcept, ConceptEditModel editModel, Resource oldConcept,
                             Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        Property definingProp = model.createProperty(OFN_NAMESPACE + DEFINUJICI_USTANOVENI);
        Property relatedProp = model.createProperty(OFN_NAMESPACE + SOUVISEJICI_USTANOVENI);

        updateLegalSourceList(newConcept, definingProp, editModel.getDefiningLegalSource(),
                oldConcept, model, toRemove, toAdd);
        updateLegalSourceList(newConcept, relatedProp, editModel.getRelatedLegalSource(),
                oldConcept, model, toRemove, toAdd);
    }

    void updateNonLegalSources(Resource newConcept, ConceptEditModel editModel, Resource oldConcept,
                                Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        Property definingProp = model.createProperty(iriFactory.getEffectiveNamespace() + DEFINUJICI_NELEGISLATIVNI_ZDROJ);
        Property relatedProp = model.createProperty(iriFactory.getEffectiveNamespace() + SOUVISEJICI_NELEGISLATIVNI_ZDROJ);

        updateNonLegalSourceList(newConcept, definingProp, editModel.getDefiningNonLegalSource(),
                oldConcept, model, toRemove, toAdd);
        updateNonLegalSourceList(newConcept, relatedProp, editModel.getRelatedNonLegalSource(),
                oldConcept, model, toRemove, toAdd);
    }

    private void updateLegalSourceList(Resource newConcept, Property property, List<String> newSources,
                                        Resource oldConcept, Model model, Set<Statement> toRemove,
                                        Set<Statement> toAdd) {
        if (newSources == null) return;

        Set<String> oldSourceURIs = getResourceURIs(oldConcept, property);
        Set<String> newSourceURIs = new HashSet<>();

        for (String source : newSources) {
            if (source == null || source.trim().isEmpty()) continue;
            String canonical = EsbirkaEliParser.canonicalizeHost(source.trim());
            if (SparqlIriValidator.isEsbirkaEliIri(canonical)) {
                newSourceURIs.add(canonical);
            } else {
                log.warn("Skipping legal source — not a canonical e-Sbírka ELI IRI: {}", source.trim());
            }
        }

        if (newSources.isEmpty() || newSourceURIs.isEmpty()) {
            if (!oldSourceURIs.isEmpty()) {
                removeAllByPredicate(newConcept, property, toRemove, toAdd);
            }
            return;
        }

        if (!oldSourceURIs.equals(newSourceURIs)) {
            removeAllByPredicate(newConcept, property, toRemove, toAdd);
            for (String sourceURI : newSourceURIs) {
                toAdd.add(model.createStatement(newConcept, property, model.createResource(sourceURI)));
            }
        }
    }

    private void updateNonLegalSourceList(Resource newConcept, Property property, List<DigitalObjectModel> newSources,
                                           Resource oldConcept, Model model, Set<Statement> toRemove,
                                           Set<Statement> toAdd) {
        if (newSources == null) return;

        List<DigitalObjectModel> validSources = newSources.stream()
                .filter(this::isValidDigitalObject)
                .toList();

        long skipped = newSources.stream().filter(d -> !isValidDigitalObject(d)).count();
        if (skipped > 0) {
            log.warn("Skipped {} non-legal source(s) — url is blank or invalid", skipped);
        }

        if (validSources.isEmpty()) {
            removeAllByPredicate(oldConcept, property, toRemove, toAdd);
            return;
        }

        removeAllByPredicate(newConcept, property, toRemove, toAdd);

        Resource digitalObjectType = model.createResource(DIGITALNI_OBJEKT);
        Property schemaUrlProperty = model.createProperty(SCHEMA_URL);
        Property dctermsTitle = model.createProperty(DCT_NS + "title");
        Property dctermsDescription = model.createProperty(DCT_NS + "description");

        for (DigitalObjectModel source : validSources) {
            Resource digitalDocument = model.createResource();

            toAdd.add(model.createStatement(digitalDocument, RDF.type, digitalObjectType));
            toAdd.add(model.createStatement(digitalDocument, schemaUrlProperty,
                    model.createResource(source.getUrl().trim())));

            if (source.getName() != null && !source.getName().trim().isEmpty()) {
                toAdd.add(model.createStatement(digitalDocument, dctermsTitle,
                        model.createLiteral(source.getName().trim(), DEFAULT_LANG)));
            }
            if (source.getDescription() != null && !source.getDescription().trim().isEmpty()) {
                toAdd.add(model.createStatement(digitalDocument, dctermsDescription,
                        model.createLiteral(source.getDescription().trim(), DEFAULT_LANG)));
            }

            toAdd.add(model.createStatement(newConcept, property, digitalDocument));
        }
    }

    private boolean isValidDigitalObject(DigitalObjectModel source) {
        if (source == null) return false;
        String url = source.getUrl();
        if (url == null || url.trim().isEmpty()) return false;
        return UtilityMethods.isValidUrl(url.trim());
    }
}
