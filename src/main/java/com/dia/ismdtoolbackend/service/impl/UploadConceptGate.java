package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.RejectedConceptDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.service.snapshot.NkdLinkDetector;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.validation.ConceptInputValidator;
import com.dia.ismdtoolbackend.utility.validation.ConceptInputView;
import com.dia.utility.UtilityMethods;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.dia.constants.VocabularyConstants.*;

/**
 * Applies the create/edit concept rules to the concepts of an uploaded vocabulary, so an
 * upload cannot import a concept in a state the edit path rejects.
 *
 * <p>Two rule sets: {@link ConceptInputValidator} over the values the detail read returns for
 * the concept, and the edit path's ban on a domain/range that points at a concept published
 * in NKD.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UploadConceptGate {

    private final OntologyDetailExtractor detailExtractor;
    private final NkdLinkDetector nkdLinkDetector;
    private final ConceptMetadataRepository conceptMetadataRepository;

    /**
     * The domain/range targets outside the vocabulary that must not be published in NKD.
     * Targets owned locally are left out: a working copy is published, but it is ours.
     *
     * @param concepts the upload's owned concepts and their resolved types
     * @return concept IRI → its candidate targets; concepts without any are absent
     */
    public Map<String, List<String>> foreignDomainRangeTargets(Model model, String graphName,
                                                               Map<String, ConceptType> concepts) {
        Map<String, List<String>> targets = new LinkedHashMap<>();
        concepts.forEach((iri, type) -> {
            List<String> found = nkdLinkDetector.forbiddenDomainRangeTargets(iri, type, graphName, model);
            if (!found.isEmpty()) targets.put(iri, found);
        });
        if (targets.isEmpty()) {
            return targets;
        }

        Set<String> all = targets.values().stream().flatMap(List::stream).collect(Collectors.toSet());
        Set<String> locallyOwned = conceptMetadataRepository.findByConceptIriIn(new ArrayList<>(all)).stream()
                .map(ConceptMetadataEntity::getConceptIri)
                .collect(Collectors.toSet());
        targets.replaceAll((iri, found) -> found.stream().filter(t -> !locallyOwned.contains(t)).toList());
        targets.values().removeIf(List::isEmpty);
        return targets;
    }

    /**
     * @param concepts        the upload's owned concepts and their resolved types
     * @param domainRangeTargets the result of {@link #foreignDomainRangeTargets}
     * @param publishedInNkd  the IRIs NKD reported as published
     * @return one entry per concept that breaks a rule, listing every broken rule
     */
    public List<RejectedConceptDto> findInvalid(Model model, Map<String, ConceptType> concepts,
                                                Map<String, List<String>> domainRangeTargets,
                                                Collection<String> publishedInNkd) {
        if (concepts.isEmpty()) {
            return List.of();
        }
        Map<String, OntologyDetailModel.ConceptDetailModel> details = readDetails(model);

        List<RejectedConceptDto> rejected = new ArrayList<>();
        concepts.forEach((iri, type) -> {
            List<String> problems = new ArrayList<>();

            OntologyDetailModel.ConceptDetailModel detail = details.get(iri);
            if (detail != null) {
                Resource concept = model.getResource(iri);
                ConceptInputView view = ConceptInputView.of(
                        detail, type, privacyProvisions(concept), isPublic(concept));
                ConceptInputValidator.validate(view).forEach(problem -> problems.add(problem.toString()));
            }

            List<String> published = domainRangeTargets.getOrDefault(iri, List.of()).stream()
                    .filter(publishedInNkd::contains)
                    .toList();
            if (!published.isEmpty()) {
                problems.add("definiční obor / obor hodnot odkazuje na publikovaný pojem v NKD: " + published);
            }

            if (!problems.isEmpty()) {
                rejected.add(new RejectedConceptDto(iri, String.join("; ", problems)));
            }
        });
        return rejected;
    }

    private Map<String, OntologyDetailModel.ConceptDetailModel> readDetails(Model model) {
        Model ofnModel = detailExtractor.applyOFNTransformations(model);
        try {
            return detailExtractor.extractOntologyDetail(ofnModel, iri -> null).getConcepts().stream()
                    .filter(detail -> detail.getIri() != null)
                    .collect(Collectors.toMap(OntologyDetailModel.ConceptDetailModel::getIri,
                            Function.identity(), (first, second) -> first));
        } finally {
            ofnModel.close();
        }
    }

    /** Provisions under either predicate the writers and the OFN export use, in any namespace. */
    private static List<String> privacyProvisions(Resource concept) {
        Set<String> provisions = new LinkedHashSet<>();
        for (Statement stmt : concept.listProperties().toList()) {
            String localName = UtilityMethods.extractNameFromIRI(stmt.getPredicate().getURI());
            if (USTANOVENI_NEVEREJNOST.equals(localName) || USTANOVENI_LONG.equals(localName)) {
                RDFNode object = stmt.getObject();
                provisions.add(object.isURIResource() ? object.asResource().getURI()
                        : object.isLiteral() ? object.asLiteral().getLexicalForm() : object.toString());
            }
        }
        return new ArrayList<>(provisions);
    }

    /** True/false from the veřejný/neveřejný-údaj type; null when the concept carries neither. */
    private static Boolean isPublic(Resource concept) {
        Model model = concept.getModel();
        if (concept.hasProperty(RDF.type, model.createResource(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ))) {
            return Boolean.TRUE;
        }
        if (concept.hasProperty(RDF.type, model.createResource(OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ))) {
            return Boolean.FALSE;
        }
        return null;
    }
}
