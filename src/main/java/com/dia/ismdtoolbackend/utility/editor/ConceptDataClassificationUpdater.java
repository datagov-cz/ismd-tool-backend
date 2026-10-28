package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.ofn.OfnIris;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaEliParser;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static com.dia.ismdtoolbackend.utility.editor.ConceptEditStatements.*;
import static com.dia.ismdtoolbackend.utility.editor.RdfLangValues.removeAllByPredicate;

/**
 * Edits the rdf:type classification of a concept (subjekt/objekt, veřejný/neveřejný) and its privacy
 * provisions. Writes the OFN shapes only; the pre-OFN copies of an edited field are removed.
 */
@Slf4j
class ConceptDataClassificationUpdater {

    /** Pre-OFN provision predicates: either local name, in any namespace but the OFN term's. */
    private static final Predicate<Property> LEGACY_PROVISION = legacyCopyOf(OfnIris.USTANOVENI_NEVEREJNOST,
            OfnIris.LEGACY_USTANOVENI_LOCAL_NAME, OfnIris.localName(OfnIris.USTANOVENI_NEVEREJNOST));

    void updateClassType(Resource newConcept, String newType, Resource oldConcept,
                          Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (newType == null) return;

        String type = newType.toLowerCase();
        updateLegalType(newConcept, oldConcept, type.contains("subjekt"), OfnIris.TYP_SUBJEKTU_PRAVA,
                OfnIris.LEGACY_TYP_SUBJEKTU_PRAVA, model, toRemove, toAdd);
        updateLegalType(newConcept, oldConcept, type.contains("objekt"), OfnIris.TYP_OBJEKTU_PRAVA,
                OfnIris.LEGACY_TYP_OBJEKTU_PRAVA, model, toRemove, toAdd);
    }

    /** Stages the OFN {@code vsgov:} type when wanted and drops the legacy {@code slovníky:} one. */
    private void updateLegalType(Resource newConcept, Resource oldConcept, boolean wanted, String ofnType,
                                 String legacyType, Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        Resource ofn = model.getResource(ofnType);
        Resource legacy = model.getResource(legacyType);
        if (oldConcept.hasProperty(RDF.type, legacy)) {
            removeTypeStatement(newConcept, oldConcept, legacy, model, toRemove, toAdd);
        }
        boolean has = oldConcept.hasProperty(RDF.type, ofn);
        if (has && !wanted) {
            removeTypeStatement(newConcept, oldConcept, ofn, model, toRemove, toAdd);
        } else if (!has && wanted) {
            toAdd.add(model.createStatement(newConcept, RDF.type, ofn));
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

        Resource verejnyLegal = model.getResource(OfnIris.VEREJNY_UDAJ);
        Resource neverejnyLegal = model.getResource(OfnIris.NEVEREJNY_UDAJ);

        // Non-public needs a provision the concept ends up with: a valid one in this edit, or,
        // when the edit leaves the provisions alone, one it already carries (in any stored shape).
        Property provisionProperty = model.createProperty(OfnIris.USTANOVENI_NEVEREJNOST);
        boolean endsUpWithProvisions = privacyProvisions == null
                ? oldConcept.listProperties().toList().stream().map(Statement::getPredicate)
                        .anyMatch(p -> p.equals(provisionProperty) || LEGACY_PROVISION.test(p))
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

        Property provisionProperty = model.createProperty(OfnIris.USTANOVENI_NEVEREJNOST);
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

        if (!oldProvisions.equals(newProvisions)) {
            removeAllByPredicate(newConcept, provisionProperty, toRemove, toAdd);
            for (String provisionURI : newProvisions) {
                toAdd.add(model.createStatement(newConcept, provisionProperty, model.createResource(provisionURI)));
            }
        }
        removeLegacyPredicates(newConcept, oldConcept, LEGACY_PROVISION, toRemove, toAdd);
    }
}
