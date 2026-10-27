package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.DigitalObjectModel;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaEliParser;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import com.dia.utility.UtilityMethods;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;
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

        Property schemaUrlProperty = model.createProperty(SCHEMA_URL);
        Property dctermsTitle = model.createProperty(DCT_NS + "title");
        Property dctermsDescription = model.createProperty(DCT_NS + "description");

        Set<SourceRecord> wanted = new HashSet<>();
        validSources.forEach(source -> wanted.add(SourceRecord.of(source)));
        Set<SourceRecord> current = currentSources(oldConcept, property, schemaUrlProperty, dctermsTitle,
                dctermsDescription);
        if (wanted.equals(current)) {
            return;
        }

        removeSources(newConcept, oldConcept, property, toRemove, toAdd);
        if (validSources.isEmpty()) {
            return;
        }

        Resource digitalObjectType = model.createResource(DIGITALNI_OBJEKT);

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

    /** One non-legal source as compared between the stored concept and the edit; blank text is {@code null}. */
    private record SourceRecord(String url, String title, String description) {

        static SourceRecord of(DigitalObjectModel source) {
            return new SourceRecord(source.getUrl().trim(), blankToNull(source.getName()),
                    blankToNull(source.getDescription()));
        }

        private static String blankToNull(String value) {
            return value == null || value.trim().isEmpty() ? null : value.trim();
        }
    }

    /**
     * The concept's stored sources under {@code property}, or {@code null} when one is not a digital-object
     * blank node, so it never equals an edit and gets rewritten.
     */
    private Set<SourceRecord> currentSources(Resource concept, Property property, Property url,
                                             Property title, Property description) {
        Set<SourceRecord> sources = new HashSet<>();
        for (Statement link : concept.listProperties(property).toList()) {
            if (!link.getObject().isAnon()) {
                return null;
            }
            Resource document = link.getObject().asResource();
            Statement urlValue = document.getProperty(url);
            if (urlValue == null) {
                return null;
            }
            sources.add(new SourceRecord(lexical(urlValue), text(document, title), text(document, description)));
        }
        return sources;
    }

    private static String text(Resource document, Property property) {
        Statement value = document.getProperty(property);
        return value == null ? null : SourceRecord.blankToNull(lexical(value));
    }

    private static String lexical(Statement statement) {
        RDFNode object = statement.getObject();
        return object.isLiteral() ? object.asLiteral().getLexicalForm() : object.toString();
    }

    /** Unlinks every source under {@code property} and removes the blank-node documents it pointed to. */
    private void removeSources(Resource newConcept, Resource oldConcept, Property property,
                               Set<Statement> toRemove, Set<Statement> toAdd) {
        List<RDFNode> documents = new java.util.ArrayList<>();
        oldConcept.listProperties(property).forEachRemaining(link -> documents.add(link.getObject()));
        newConcept.listProperties(property).forEachRemaining(link -> documents.add(link.getObject()));
        removeAllByPredicate(newConcept, property, toRemove, toAdd);
        for (RDFNode document : documents) {
            if (document.isAnon()) {
                toRemove.addAll(document.asResource().listProperties().toList());
            }
        }
    }

    private boolean isValidDigitalObject(DigitalObjectModel source) {
        if (source == null) return false;
        String url = source.getUrl();
        if (url == null || url.trim().isEmpty()) return false;
        return UtilityMethods.isValidUrl(url.trim());
    }
}
