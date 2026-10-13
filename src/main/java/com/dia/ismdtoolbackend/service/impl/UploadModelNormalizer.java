package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.MissingConceptDto;
import com.dia.ismdtoolbackend.enums.NormalizeMode;
import com.dia.ismdtoolbackend.exception.InSchemeDecisionRequiredException;
import com.dia.ismdtoolbackend.exception.OntologyUploadMissingIriException;
import com.dia.ismdtoolbackend.utility.exporter.turtle.OFNTypeNormalizer;
import com.dia.ismdtoolbackend.utility.published.PublishedResourceUtil;
import com.dia.utility.UtilityMethods;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Derives the vocabulary's identity from an uploaded model and resolves the {@code skos:inScheme} decision. */
@Component
@Slf4j
class UploadModelNormalizer {

    String determineGraphName(OntModel model) {
        // The RDF data is authoritative for the vocabulary IRI / graph name. If no
        // ontology IRI can be derived, FAIL — never fabricate a filename-based name,
        // which produces orphan concepts whose namespace diverges from the graph.
        String ontologyIRI = extractOntologyIRI(model);
        if (ontologyIRI == null) {
            throw new OntologyUploadMissingIriException(
                    "Z RDF dat nelze odvodit IRI slovníku (chybí owl:Ontology nebo skos:ConceptScheme). "
                            + "Slovník nelze nahrát bez identity odvozené z dat.");
        }
        log.debug("Using extracted ontology IRI as graph name: {}", ontologyIRI);
        return ontologyIRI;
    }

    /**
     * The missing-{@code skos:inScheme} concepts to stamp, from the user's decision. Throws when
     * concepts are missing it and no decision was sent.
     */
    Set<String> conceptsToStamp(OntModel finalModel, String graphName, NormalizeMode normalizeMode,
                                List<String> conceptsToNormalize) {
        List<String> missingInScheme = OFNTypeNormalizer.detectOwnedConceptsMissingInScheme(finalModel, graphName);
        if (!missingInScheme.isEmpty() && normalizeMode == null) {
            List<MissingConceptDto> missingDtos = missingInScheme.stream()
                    .map(iri -> new MissingConceptDto(
                            iri, UtilityMethods.extractNameFromIRI(iri), graphName))
                    .toList();
            log.info("Upload requires inScheme decision: {} concept(s) missing skos:inScheme in {}",
                    missingDtos.size(), graphName);
            throw new InSchemeDecisionRequiredException(graphName, missingDtos);
        }

        return resolveConceptsToNormalize(missingInScheme, normalizeMode, conceptsToNormalize);
    }

    /**
     * Maps the user's {@link NormalizeMode} decision to the set of missing-inScheme
     * concept IRIs that should be normalized (stamped with {@code inScheme → graphName}).
     * The complement — missing concepts NOT returned here — are excluded: no inScheme,
     * no ownership row, triples kept.
     */
    private Set<String> resolveConceptsToNormalize(List<String> missingInScheme,
                                                   NormalizeMode normalizeMode,
                                                   List<String> conceptsToNormalize) {
        if (missingInScheme.isEmpty()) {
            return Set.of();
        }
        Set<String> missingSet = new HashSet<>(missingInScheme);
        // normalizeMode is non-null here (the decision gate above returned otherwise).
        return switch (normalizeMode) {
            case NORMALIZE_ALL -> missingSet;
            case EXCLUDE_ALL -> Set.of();
            case PER_CONCEPT -> {
                if (conceptsToNormalize == null) {
                    yield Set.of();
                }
                // Only honor IRIs that are actually in the missing set; ignore unknowns.
                Set<String> chosen = new HashSet<>(conceptsToNormalize);
                chosen.retainAll(missingSet);
                yield chosen;
            }
        };
    }

    /**
     * Stamps {@code owl:Ontology} on the vocabulary resource when the uploaded data omits it.
     *
     * <p>OFN vocabularies commonly declare only {@code skos:ConceptScheme} and the OFN
     * {@code …/pojem/slovník} type. {@code owl:Ontology} is the foundational type every
     * ontology-keyed lookup asks for — {@link PublishedResourceUtil#checkPublishedResourcesInNKD}
     * among them, which decides {@code isPublished}. The create path stamps all three types; upload
     * must land in the same shape.
     */
    void normalizeOntologyType(OntModel model, String graphName) {
        Resource ontologyResource = model.getResource(graphName);
        if (!ontologyResource.hasProperty(RDF.type, OWL2.Ontology)) {
            ontologyResource.addProperty(RDF.type, OWL2.Ontology);
            log.info("Normalized ontology {} to owl:Ontology (absent from uploaded data)", graphName);
        }
    }

    private String extractOntologyIRI(OntModel model) {
        // owl:Ontology is the primary signal; SKOS-only vocabularies declare the
        // vocabulary as a skos:ConceptScheme instead, so accept that too.
        String iri = firstUriSubjectOfType(model, OWL2.Ontology);
        if (iri == null) {
            iri = firstUriSubjectOfType(model, SKOS.ConceptScheme);
        }
        return iri;
    }

    private String firstUriSubjectOfType(OntModel model, Resource type) {
        ResIterator subjects = model.listResourcesWithProperty(RDF.type, type);
        while (subjects.hasNext()) {
            Resource subject = subjects.next();
            if (subject.isURIResource()) {
                return subject.getURI();
            }
        }
        return null;
    }
}
