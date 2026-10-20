package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.RejectedConceptDto;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.utility.exporter.turtle.OFNTypeNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.dia.constants.VocabularyConstants.*;

/** Resolves the role of each concept in an upload and takes the unresolvable ones out of the vocabulary. */
@Component
@Slf4j
class UploadConceptRoleResolver {

    static final String POJEM_GENERIC = "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/pojem";

    /**
     * The upload's owned concepts — in the vocabulary's namespace and carrying
     * {@code skos:inScheme} — with their resolved type. A concept whose role cannot be resolved
     * is added to {@code rejected} instead.
     */
    Map<String, ConceptType> resolveOwnedConcepts(OntModel model, String graphName,
                                                          List<RejectedConceptDto> rejected) {
        Map<String, ConceptType> owned = new LinkedHashMap<>();
        ResIterator concepts = model.listResourcesWithProperty(RDF.type, model.createResource(POJEM_GENERIC));
        while (concepts.hasNext()) {
            Resource concept = concepts.next();
            if (!concept.isURIResource()
                    || !OFNTypeNormalizer.isOwnedConcept(concept.getURI(), graphName)
                    || !concept.hasProperty(SKOS.inScheme)) {
                continue;
            }
            RoleResolution role = resolveRole(concept);
            if (role.conceptType() == null) {
                rejected.add(new RejectedConceptDto(concept.getURI(), role.reason()));
            } else {
                owned.put(concept.getURI(), role.conceptType());
            }
        }
        return owned;
    }

    /** Strips {@code skos:inScheme} from every rejected concept and logs what was left out. */
    void excludeRejectedConcepts(OntModel model, String graphName, List<RejectedConceptDto> rejected) {
        if (rejected.isEmpty()) {
            return;
        }
        for (RejectedConceptDto concept : rejected) {
            model.removeAll(model.getResource(concept.iri()), SKOS.inScheme, null);
        }
        log.warn("{} concept(s) were rejected from ontology {}:{}{}",
                rejected.size(), graphName, System.lineSeparator(),
                rejected.stream()
                        .map(c -> "Concept with IRI (" + c.iri() + ") rejected, " + c.reason())
                        .collect(Collectors.joining(System.lineSeparator())));
    }

    /**
     * The outcome of reading a concept's role: the resolved type, or {@code null} with the reason
     * it could not be resolved. {@code reason} is null exactly when {@code conceptType} is not.
     */
    record RoleResolution(ConceptType conceptType, String reason) {
        static RoleResolution of(ConceptType type) {
            return new RoleResolution(type, null);
        }

        static RoleResolution rejected(String reason) {
            return new RoleResolution(null, reason);
        }
    }

    /**
     * Resolves a concept's role via {@link ConceptType#fromRdfTypes}, the shared mapping that
     * accepts both the OFN role IRIs ISMD writes and the OWL types imported data carries.
     * {@link OFNTypeNormalizer#normalize} has already stamped the role tags by the time this runs,
     * so an OFN concept with no matching OWL type still resolves.
     *
     * <p>Rejects when no single role can be read — either nothing recognizable is present
     * ({@code KONCEPT}, the base type every concept carries, which is terminal and not
     * role-narrowing), or the data claims two roles at once. Contradictory data is broken input,
     * not something to repair by picking a winner.
     */
    RoleResolution resolveRole(Resource conceptResource) {
        List<String> types = conceptResource.listProperties(RDF.type)
                .toList().stream()
                .map(Statement::getObject)
                .filter(RDFNode::isURIResource)
                .map(o -> o.asResource().getURI())
                .toList();

        Set<ConceptType> declared = declaredRoles(conceptResource, types);
        if (declared.size() > 1) {
            String conflicting = declared.stream().map(ConceptType::name).sorted()
                    .collect(java.util.stream.Collectors.joining(" + "));
            return RoleResolution.rejected("conflicting types: " + conflicting);
        }

        ConceptType resolved = ConceptType.fromRdfTypes(types);
        return resolved == ConceptType.KONCEPT
                ? RoleResolution.rejected("no recognized type")
                : RoleResolution.of(resolved);
    }

    /**
     * The distinct roles a concept declares, across both the OFN role tags and their OWL
     * equivalents. A well-formed concept declares exactly one; more means the vocabulary claims
     * the concept is, say, both a vlastnost and a vztah.
     */
    private Set<ConceptType> declaredRoles(Resource conceptResource, List<String> types) {
        Model model = conceptResource.getModel();
        Set<ConceptType> roles = EnumSet.noneOf(ConceptType.class);

        if (types.contains(OWL2.Class.getURI())
                || conceptResource.hasProperty(RDF.type, model.createResource(OFN_NAMESPACE + TRIDA))) {
            roles.add(ConceptType.TRIDA);
        }
        if (types.contains(OWL2.DatatypeProperty.getURI())
                || conceptResource.hasProperty(RDF.type, model.createResource(OFN_NAMESPACE + VLASTNOST))) {
            roles.add(ConceptType.VLASTNOST);
        }
        if (types.contains(OWL2.ObjectProperty.getURI())
                || conceptResource.hasProperty(RDF.type, model.createResource(OFN_NAMESPACE + VZTAH))) {
            roles.add(ConceptType.VZTAH);
        }
        return roles;
    }
}
