package com.dia.ismdtoolbackend.utility.validation;

import com.dia.ismdtoolbackend.controller.dto.CodeListDto;
import com.dia.ismdtoolbackend.controller.dto.NonLegalSourceDto;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptValidationUtil;
import com.dia.ismdtoolbackend.models.concept.DigitalObjectModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptModel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The validated field surface of a concept payload, flattened out of the create and
 * edit model hierarchies so {@link ConceptInputValidator} can hold one rule set for
 * both verbs. The two hierarchies carry the same fields but share no supertype, and
 * the FE client is generated from those models — so they are adapted here rather
 * than restructured.
 *
 * <p>{@code entityName} and {@code genderSuffix} carry the Czech noun and adjective
 * ending used to phrase per-type validation messages.
 *
 * @param codeListIri     class-only — OFN scopes "má instance definované číselníkem" to Třída;
 *                        null for property and relationship payloads
 * @param codeListDataset class-only, as above
 * @param references      the concept-reference fields by name (domain, range, broader, super-property or
 *                        -relation); each value must be an IRI. A Vlastnost's datatype range is not one.
 */
public record ConceptInputView(
        List<String> definingLegalSource,
        List<String> relatedLegalSource,
        List<DigitalObjectModel> definingNonLegalSource,
        List<DigitalObjectModel> relatedNonLegalSource,
        List<String> exactMatch,
        List<String> privacyProvisions,
        Boolean isPublic,
        String agendaCode,
        String agendaSystemCode,
        List<String> sharingMethod,
        String acquisitionMethod,
        String contentType,
        String codeListIri,
        String codeListDataset,
        String entityName,
        String genderSuffix,
        Map<String, List<String>> references) {

    /** Adapts a create payload; returns null when the concrete type is unrecognized. */
    public static ConceptInputView of(ConceptCreateModel m) {
        if (m instanceof ClassConceptModel c) {
            return build(m, c.getPrivacyProvisions(), c.getIsPublic(), c.getAgendaCode(),
                    c.getAgendaSystemCode(), c.getSharingMethod(), c.getAcquisitionMethod(),
                    c.getContentType(), c.getCodeListIri(), c.getCodeListDataset(), "Třída", "á",
                    references("broaderConcept", c.getBroaderConcept()));
        }
        if (m instanceof PropertyConceptModel p) {
            return build(m, p.getPrivacyProvisions(), p.getIsPublic(), p.getAgendaCode(),
                    p.getAgendaSystemCode(), p.getSharingMethod(), p.getAcquisitionMethod(),
                    p.getContentType(), null, null, "Vlastnost", "á",
                    references("domain", one(p.getDomain()), "superProperty", p.getSuperProperty()));
        }
        if (m instanceof RelationshipConceptModel r) {
            return build(m, r.getPrivacyProvisions(), r.getIsPublic(), r.getAgendaCode(),
                    r.getAgendaSystemCode(), r.getSharingMethod(), r.getAcquisitionMethod(),
                    r.getContentType(), null, null, "Vztah", "ý",
                    references("domain", one(r.getDomain()), "range", one(r.getRange()),
                            "superRelation", r.getSuperRelation()));
        }
        return null;
    }

    /**
     * Adapts a concept read from stored or uploaded RDF — the values the detail read returns
     * and an edit would send back.
     *
     * @param privacyProvisions read from the graph by the caller; the detail read does not carry them
     * @param isPublic          from the veřejný/neveřejný-údaj type; null when the concept has neither
     */
    public static ConceptInputView of(OntologyDetailModel.ConceptDetailModel d, ConceptType type,
                                      List<String> privacyProvisions, Boolean isPublic) {
        boolean isClass = type == ConceptType.TRIDA;
        CodeListDto codeList = isClass ? d.getCodeList() : null;
        return new ConceptInputView(
                d.getDefiningLegalSources(), d.getRelatedLegalSources(),
                digitalObjects(d.getDefiningNonLegalSources()), digitalObjects(d.getRelatedNonLegalSources()),
                d.getExactMatches(), privacyProvisions, isPublic,
                d.getAgenda(), d.getAis(),
                d.getSharingMethods() == null ? null
                        : d.getSharingMethods().stream().map(ConceptValidationUtil::governanceValueOf).toList(),
                ConceptValidationUtil.governanceValueOf(d.getAcquisitionMethod()),
                ConceptValidationUtil.governanceValueOf(d.getContentType()),
                codeList == null ? null : codeList.getIri(),
                codeList == null ? null : codeList.getDatovaSadaVNkod(),
                isClass ? "Třída" : type == ConceptType.VZTAH ? "Vztah" : "Vlastnost",
                type == ConceptType.VZTAH ? "ý" : "á",
                references("domain", one(d.getDomain()),
                        "range", type == ConceptType.VZTAH ? one(d.getRange()) : null,
                        "broaderConcept", d.getBroaderClasses(),
                        "superProperty", d.getBroaderProperties(),
                        "superRelation", d.getBroaderRelations()));
    }

    /** A stored digital object may have no URL; only a URL that is present is validated. */
    private static List<DigitalObjectModel> digitalObjects(List<NonLegalSourceDto> sources) {
        if (sources == null) return null;
        return sources.stream().filter(source -> source.getUrl() != null && !source.getUrl().isBlank()).map(source -> {
            DigitalObjectModel model = new DigitalObjectModel();
            model.setUrl(source.getUrl());
            return model;
        }).toList();
    }

    /** Adapts an edit payload; returns null when the concrete type is unrecognized. */
    public static ConceptInputView of(ConceptEditModel m) {
        if (m instanceof ClassConceptEditModel c) {
            return build(m, c.getPrivacyProvisions(), c.getIsPublic(), c.getAgendaCode(),
                    c.getAgendaSystemCode(), c.getSharingMethod(), c.getAcquisitionMethod(),
                    c.getContentType(), c.getCodeListIri(), c.getCodeListDataset(), "Třída", "á",
                    references("broaderConcept", c.getBroaderConcept()));
        }
        if (m instanceof PropertyConceptEditModel p) {
            return build(m, p.getPrivacyProvisions(), p.getIsPublic(), p.getAgendaCode(),
                    p.getAgendaSystemCode(), p.getSharingMethod(), p.getAcquisitionMethod(),
                    p.getContentType(), null, null, "Vlastnost", "á",
                    references("domain", one(p.getDomain()), "superProperty", p.getSuperProperty()));
        }
        if (m instanceof RelationshipConceptEditModel r) {
            return build(m, r.getPrivacyProvisions(), r.getIsPublic(), r.getAgendaCode(),
                    r.getAgendaSystemCode(), r.getSharingMethod(), r.getAcquisitionMethod(),
                    r.getContentType(), null, null, "Vztah", "ý",
                    references("domain", one(r.getDomain()), "range", one(r.getRange()),
                            "superRelation", r.getSuperRelation()));
        }
        return null;
    }

    private static ConceptInputView build(ConceptCreateModel base,
                                          List<String> privacyProvisions, Boolean isPublic,
                                          String agendaCode, String agendaSystemCode,
                                          List<String> sharingMethod, String acquisitionMethod,
                                          String contentType, String codeListIri, String codeListDataset,
                                          String entityName, String genderSuffix,
                                          Map<String, List<String>> references) {
        return new ConceptInputView(
                base.getDefiningLegalSource(), base.getRelatedLegalSource(),
                base.getDefiningNonLegalSource(), base.getRelatedNonLegalSource(),
                base.getExactMatch(), privacyProvisions, isPublic,
                agendaCode, agendaSystemCode, sharingMethod, acquisitionMethod, contentType,
                codeListIri, codeListDataset, entityName, genderSuffix, references);
    }

    private static ConceptInputView build(ConceptEditModel base,
                                          List<String> privacyProvisions, Boolean isPublic,
                                          String agendaCode, String agendaSystemCode,
                                          List<String> sharingMethod, String acquisitionMethod,
                                          String contentType, String codeListIri, String codeListDataset,
                                          String entityName, String genderSuffix,
                                          Map<String, List<String>> references) {
        return new ConceptInputView(
                base.getDefiningLegalSource(), base.getRelatedLegalSource(),
                base.getDefiningNonLegalSource(), base.getRelatedNonLegalSource(),
                base.getExactMatch(), privacyProvisions, isPublic,
                agendaCode, agendaSystemCode, sharingMethod, acquisitionMethod, contentType,
                codeListIri, codeListDataset, entityName, genderSuffix, references);
    }

    private static List<String> one(String value) {
        return value == null ? null : List.of(value);
    }

    /** Field name → values, in the given order; a null list is left out. */
    private static Map<String, List<String>> references(Object... nameValuePairs) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            List<String> values = (List<String>) nameValuePairs[i + 1];
            if (values != null) {
                out.put((String) nameValuePairs[i], values);
            }
        }
        return out;
    }
}
