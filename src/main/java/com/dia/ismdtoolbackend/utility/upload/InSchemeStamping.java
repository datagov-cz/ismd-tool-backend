package com.dia.ismdtoolbackend.utility.upload;

import com.dia.ismdtoolbackend.controller.dto.MissingConceptDto;
import com.dia.ismdtoolbackend.enums.NormalizeMode;
import com.dia.ismdtoolbackend.exception.InSchemeDecisionRequiredException;
import com.dia.utility.UtilityMethods;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.SKOS;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Upload step: owned concepts without {@code skos:inScheme} get it only when the user says so. With no
 * decision sent, the upload stops and lists them ({@link InSchemeDecisionRequiredException}); a concept
 * left out is not imported (no inScheme, no metadata row).
 */
public final class InSchemeStamping {

    private InSchemeStamping() {
    }

    /** Owned concepts lacking {@code skos:inScheme}. Changes nothing. */
    public static List<String> missing(Model model, String graphName) {
        OwnedConcepts.requireGraphName(graphName);
        return OwnedConcepts.of(model, graphName).stream()
                .filter(concept -> !concept.hasProperty(SKOS.inScheme))
                .map(Resource::getURI)
                .toList();
    }

    /** The missing concepts the user chose to import; throws when some are missing and no decision was sent. */
    public static Set<String> chosen(Model model, String graphName, NormalizeMode mode, List<String> perConcept) {
        List<String> missing = missing(model, graphName);
        if (missing.isEmpty()) {
            return Set.of();
        }
        if (mode == null) {
            throw new InSchemeDecisionRequiredException(graphName, missing.stream()
                    .map(iri -> new MissingConceptDto(iri, UtilityMethods.extractNameFromIRI(iri), graphName))
                    .toList());
        }
        Set<String> missingSet = new HashSet<>(missing);
        return switch (mode) {
            case NORMALIZE_ALL -> missingSet;
            case EXCLUDE_ALL -> Set.of();
            case PER_CONCEPT -> {
                Set<String> chosen = new HashSet<>(perConcept == null ? List.of() : perConcept);
                chosen.retainAll(missingSet);
                yield chosen;
            }
        };
    }

    /** Adds {@code skos:inScheme <graphName>} to the chosen concepts that lack it. Returns how many. */
    public static int stamp(Model model, String graphName, Set<String> chosen) {
        Resource vocabulary = model.createResource(graphName);
        int stamped = 0;
        for (String iri : missing(model, graphName)) {
            if (chosen.contains(iri)) {
                model.createResource(iri).addProperty(SKOS.inScheme, vocabulary);
                stamped++;
            }
        }
        return stamped;
    }
}
