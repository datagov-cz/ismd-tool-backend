package com.dia.ismdtoolbackend.utility.editor;

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
import static com.dia.ismdtoolbackend.utility.editor.ConceptEditStatements.*;
import static com.dia.ismdtoolbackend.utility.editor.RdfLangValues.removeAllByPredicate;

/** Edits the rdf:type classification of a concept (subjekt/objekt, veřejný/neveřejný) and its privacy provisions. */
@Slf4j
class ConceptDataClassificationUpdater {

    private final ConceptIriFactory iriFactory;

    ConceptDataClassificationUpdater(ConceptIriFactory iriFactory) {
        this.iriFactory = iriFactory;
    }

    void updateClassType(Resource newConcept, String newType, Resource oldConcept,
                          Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (newType == null) return;

        Resource tspType = model.getResource(OFN_NAMESPACE + TSP);
        Resource topType = model.getResource(OFN_NAMESPACE + TOP);

        boolean oldHasTSP = oldConcept.hasProperty(RDF.type, tspType);
        boolean oldHasTOP = oldConcept.hasProperty(RDF.type, topType);

        boolean newHasTSP = newType.toLowerCase().contains("subjekt");
        boolean newHasTOP = newType.toLowerCase().contains("objekt");

        if (oldHasTSP && !newHasTSP) {
            removeTypeStatement(newConcept, oldConcept, tspType, model, toRemove, toAdd);
        }
        if (oldHasTOP && !newHasTOP) {
            removeTypeStatement(newConcept, oldConcept, topType, model, toRemove, toAdd);
        }
        if (!oldHasTSP && newHasTSP) {
            toAdd.add(model.createStatement(newConcept, RDF.type, tspType));
        }
        if (!oldHasTOP && newHasTOP) {
            toAdd.add(model.createStatement(newConcept, RDF.type, topType));
        }
    }

    void updateDataClassification(Resource newConcept, Boolean isPublic, List<String> privacyProvisions,
                                          Resource oldConcept, Model model, Set<Statement> toRemove,
                                          Set<Statement> toAdd) {
        boolean hasNonEmptyProvisionsArg = privacyProvisions != null &&
                privacyProvisions.stream().anyMatch(p -> p != null && !p.trim().isEmpty());
        // Field-scoped edits (e.g. the diagram overlay) omit the classification entirely. Without this
        // guard the strip below removes the veřejný/neveřejný rdf:type and never re-adds it, silently
        // dropping the classification. Leave it untouched when nothing about it is being edited.
        if (isPublic == null && !hasNonEmptyProvisionsArg) {
            return;
        }

        Resource verejnyLegal = model.getResource(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ);
        Resource neverejnyLegal = model.getResource(OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ);

        // Non-public needs a provision the concept ends up with: a valid one in this edit, or,
        // when the edit leaves the provisions alone, one it already carries.
        Property provisionProperty = model.createProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST);
        boolean endsUpWithProvisions = privacyProvisions == null
                ? oldConcept.hasProperty(provisionProperty)
                : privacyProvisions.stream().anyMatch(p -> p != null && !p.trim().isEmpty()
                        && SparqlIriValidator.isEsbirkaEliIri(EsbirkaEliParser.canonicalizeHost(p.trim())));

        Resource wanted = null;
        if (Boolean.TRUE.equals(isPublic) && !hasNonEmptyProvisionsArg) {
            wanted = verejnyLegal;
        } else if (Boolean.FALSE.equals(isPublic) && endsUpWithProvisions) {
            wanted = neverejnyLegal;
        }

        // Only the difference is staged, so an edit that keeps the classification changes nothing.
        for (Resource classification : List.of(verejnyLegal, neverejnyLegal)) {
            boolean has = oldConcept.hasProperty(RDF.type, classification);
            if (has && !classification.equals(wanted)) {
                removeTypeStatement(newConcept, oldConcept, classification, model, toRemove, toAdd);
            } else if (!has && classification.equals(wanted)) {
                toAdd.add(model.createStatement(newConcept, RDF.type, classification));
            }
        }
    }

    void updatePrivacyProvisionsList(Resource newConcept, List<String> privacyProvisions,
                                             Resource oldConcept, Model model, Set<Statement> toRemove,
                                             Set<Statement> toAdd) {
        if (privacyProvisions == null) return;

        Property provisionProperty = model.createProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST);
        Set<String> oldProvisions = getResourceURIs(oldConcept, provisionProperty);
        Set<String> newProvisions = new HashSet<>();

        for (String provision : privacyProvisions) {
            if (provision == null || provision.trim().isEmpty()) continue;
            String canonical = EsbirkaEliParser.canonicalizeHost(provision.trim());
            if (SparqlIriValidator.isEsbirkaEliIri(canonical)) {
                newProvisions.add(canonical);
            } else {
                log.warn("Skipping privacy provision — not a canonical e-Sbírka ELI IRI: {}", provision.trim());
            }
        }

        if (privacyProvisions.isEmpty() || newProvisions.isEmpty()) {
            if (!oldProvisions.isEmpty()) {
                removeAllByPredicate(newConcept, provisionProperty, toRemove, toAdd);
            }
        } else if (!oldProvisions.equals(newProvisions)) {
            removeAllByPredicate(newConcept, provisionProperty, toRemove, toAdd);
            for (String provisionURI : newProvisions) {
                toAdd.add(model.createStatement(newConcept, provisionProperty, model.createResource(provisionURI)));
            }
        }

        syncPrivacyProvisionCopies(newConcept, oldConcept, provisionProperty, newProvisions, model, toRemove, toAdd);
    }

    /**
     * Keeps the other stored copies of the provisions in step with the edit. Create also writes
     * them under the vocabulary's own namespace, and uploaded data may carry them under that or
     * the canonical OFN property; the vocabulary-namespace copy is rewritten to the new set and
     * any other copy is dropped. A copy already equal to the new set is left untouched, so an
     * unchanged edit stages nothing.
     */
    private void syncPrivacyProvisionCopies(Resource newConcept, Resource oldConcept, Property provisionProperty,
                                            Set<String> newProvisions, Model model,
                                            Set<Statement> toRemove, Set<Statement> toAdd) {
        Property namespaceCopy = model.createProperty(iriFactory.getEffectiveNamespace() + USTANOVENI_NEVEREJNOST);

        Set<Property> otherCopies = new HashSet<>();
        oldConcept.listProperties().forEachRemaining(stmt -> {
            Property predicate = stmt.getPredicate();
            String localName = UtilityMethods.extractNameFromIRI(predicate.getURI());
            if ((USTANOVENI_NEVEREJNOST.equals(localName) || USTANOVENI_LONG.equals(localName))
                    && !predicate.equals(provisionProperty) && !predicate.equals(namespaceCopy)) {
                otherCopies.add(predicate);
            }
        });
        for (Property copy : otherCopies) {
            removeAllByPredicate(newConcept, copy, toRemove, toAdd);
            if (!newConcept.equals(oldConcept)) {
                removeAllByPredicate(oldConcept, copy, toRemove, toAdd);
            }
        }

        if (!getResourceURIs(oldConcept, namespaceCopy).equals(newProvisions)) {
            removeAllByPredicate(newConcept, namespaceCopy, toRemove, toAdd);
            if (!newConcept.equals(oldConcept)) {
                removeAllByPredicate(oldConcept, namespaceCopy, toRemove, toAdd);
            }
            for (String provisionURI : newProvisions) {
                toAdd.add(model.createStatement(newConcept, namespaceCopy, model.createResource(provisionURI)));
            }
        }
    }
}
