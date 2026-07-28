package com.dia.ismdtoolbackend.mapper;

import com.dia.ismdtoolbackend.controller.dto.ai.AiIdReferenceDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiKnownConceptualModelDto;
import com.dia.ismdtoolbackend.enums.AiTermType;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaEliParser;
import com.dia.ismdtoolbackend.utility.eli.ParsedEli;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE;
import static com.dia.constants.VocabularyConstants.TOP;
import static com.dia.constants.VocabularyConstants.TOP_JSON_LD;
import static com.dia.constants.VocabularyConstants.TSP;
import static com.dia.constants.VocabularyConstants.TSP_JSON_LD;

@Component
public class AiKnownConceptualModelMapper {

    public AiKnownConceptualModelDto map(List<OntologyDetailModel> ontologyDetails) {
        List<OntologyDetailModel.ConceptDetailModel> concepts = ontologyDetails == null
                ? List.of()
                : ontologyDetails.stream()
                        .filter(Objects::nonNull)
                        .flatMap(detail -> detail.getConcepts() == null
                                ? Stream.empty()
                                : detail.getConcepts().stream())
                        .toList();

        return new AiKnownConceptualModelDto(
                distinctByTermId(concepts.stream()
                        .filter(concept -> ConceptType.fromRdfTypes(concept.getTypes()) == ConceptType.TRIDA)
                        .map(this::mapClass)
                        .toList(), AiKnownConceptualModelDto.KnownClassTermDto::termId),
                distinctByTermId(concepts.stream()
                        .filter(concept -> ConceptType.fromRdfTypes(concept.getTypes()) == ConceptType.VLASTNOST)
                        .map(this::mapAttribute)
                        .toList(), AiKnownConceptualModelDto.KnownAttributeTermDto::termId),
                distinctByTermId(concepts.stream()
                        .filter(concept -> ConceptType.fromRdfTypes(concept.getTypes()) == ConceptType.VZTAH)
                        .map(this::mapRelationship)
                        .toList(), AiKnownConceptualModelDto.KnownRelationshipTermDto::termId)
        );
    }

    private <T> List<T> distinctByTermId(List<T> terms, Function<T, String> termIdExtractor) {
        Set<String> seenTermIds = new HashSet<>();
        return terms.stream()
                .filter(term -> seenTermIds.add(termIdExtractor.apply(term)))
                .toList();
    }

    private AiKnownConceptualModelDto.KnownClassTermDto mapClass(
            OntologyDetailModel.ConceptDetailModel concept
    ) {
        return new AiKnownConceptualModelDto.KnownClassTermDto(
                concept.getIri(),
                concept.getName(),
                concept.getDefinition(),
                concept.getDescription(),
                mapClassType(concept.getTypes()),
                references(concept.getBroaderClasses()),
                firstDefiningLegalSource(concept)
        );
    }

    private AiKnownConceptualModelDto.KnownAttributeTermDto mapAttribute(
            OntologyDetailModel.ConceptDetailModel concept
    ) {
        return new AiKnownConceptualModelDto.KnownAttributeTermDto(
                concept.getIri(),
                reference(concept.getDomain()),
                concept.getName(),
                concept.getDefinition(),
                concept.getDescription(),
                firstDefiningLegalSource(concept)
        );
    }

    private AiKnownConceptualModelDto.KnownRelationshipTermDto mapRelationship(
            OntologyDetailModel.ConceptDetailModel concept
    ) {
        return new AiKnownConceptualModelDto.KnownRelationshipTermDto(
                concept.getIri(),
                reference(concept.getDomain()),
                reference(concept.getRange()),
                concept.getName(),
                concept.getDefinition(),
                concept.getDescription(),
                firstDefiningLegalSource(concept)
        );
    }

    private AiTermType mapClassType(List<String> types) {
        if (containsType(types, TSP_JSON_LD, OFN_NAMESPACE + TSP)) {
            return AiTermType.SUBJECT;
        }
        if (containsType(types, TOP_JSON_LD, OFN_NAMESPACE + TOP)) {
            return AiTermType.OBJECT;
        }
        return AiTermType.CLASS;
    }

    private boolean containsType(List<String> types, String... expectedTypes) {
        if (types == null) {
            return false;
        }
        for (String expectedType : expectedTypes) {
            if (types.contains(expectedType)) {
                return true;
            }
        }
        return false;
    }

    private List<AiIdReferenceDto> references(List<String> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .map(AiIdReferenceDto::new)
                .toList();
    }

    private AiIdReferenceDto reference(String id) {
        return id == null || id.isBlank() ? null : new AiIdReferenceDto(id);
    }

    private String firstDefiningLegalSource(OntologyDetailModel.ConceptDetailModel concept) {
        if (concept.getDefiningLegalSources() == null) {
            return null;
        }
        return concept.getDefiningLegalSources().stream()
                .filter(Objects::nonNull)
                .filter(source -> !source.isBlank())
                .findFirst()
                .map(this::toLegalActPath)
                .orElse(null);
    }

    private String toLegalActPath(String source) {
        ParsedEli parsed = EsbirkaEliParser.parse(source);
        if (!parsed.isValid()) {
            return source;
        }
        return "/eli/cz/" + parsed.sbirkaCode() + "/" + parsed.lawYear() + "/" + parsed.lawNumber();
    }
}
