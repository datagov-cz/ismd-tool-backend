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

import java.util.List;

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
        String genderSuffix) {

    /** Adapts a create payload; returns null when the concrete type is unrecognized. */
    public static ConceptInputView of(ConceptCreateModel m) {
        if (m instanceof ClassConceptModel c) {
            return build(m, c.getPrivacyProvisions(), c.getIsPublic(), c.getAgendaCode(),
                    c.getAgendaSystemCode(), c.getSharingMethod(), c.getAcquisitionMethod(),
                    c.getContentType(), c.getCodeListIri(), c.getCodeListDataset(), "Třída", "á");
        }
        if (m instanceof PropertyConceptModel p) {
            return build(m, p.getPrivacyProvisions(), p.getIsPublic(), p.getAgendaCode(),
                    p.getAgendaSystemCode(), p.getSharingMethod(), p.getAcquisitionMethod(),
                    p.getContentType(), null, null, "Vlastnost", "á");
        }
        if (m instanceof RelationshipConceptModel r) {
            return build(m, r.getPrivacyProvisions(), r.getIsPublic(), r.getAgendaCode(),
                    r.getAgendaSystemCode(), r.getSharingMethod(), r.getAcquisitionMethod(),
                    r.getContentType(), null, null, "Vztah", "ý");
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
                type == ConceptType.VZTAH ? "ý" : "á");
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
                    c.getContentType(), c.getCodeListIri(), c.getCodeListDataset(), "Třída", "á");
        }
        if (m instanceof PropertyConceptEditModel p) {
            return build(m, p.getPrivacyProvisions(), p.getIsPublic(), p.getAgendaCode(),
                    p.getAgendaSystemCode(), p.getSharingMethod(), p.getAcquisitionMethod(),
                    p.getContentType(), null, null, "Vlastnost", "á");
        }
        if (m instanceof RelationshipConceptEditModel r) {
            return build(m, r.getPrivacyProvisions(), r.getIsPublic(), r.getAgendaCode(),
                    r.getAgendaSystemCode(), r.getSharingMethod(), r.getAcquisitionMethod(),
                    r.getContentType(), null, null, "Vztah", "ý");
        }
        return null;
    }

    private static ConceptInputView build(ConceptCreateModel base,
                                          List<String> privacyProvisions, Boolean isPublic,
                                          String agendaCode, String agendaSystemCode,
                                          List<String> sharingMethod, String acquisitionMethod,
                                          String contentType, String codeListIri, String codeListDataset,
                                          String entityName, String genderSuffix) {
        return new ConceptInputView(
                base.getDefiningLegalSource(), base.getRelatedLegalSource(),
                base.getDefiningNonLegalSource(), base.getRelatedNonLegalSource(),
                base.getExactMatch(), privacyProvisions, isPublic,
                agendaCode, agendaSystemCode, sharingMethod, acquisitionMethod, contentType,
                codeListIri, codeListDataset, entityName, genderSuffix);
    }

    private static ConceptInputView build(ConceptEditModel base,
                                          List<String> privacyProvisions, Boolean isPublic,
                                          String agendaCode, String agendaSystemCode,
                                          List<String> sharingMethod, String acquisitionMethod,
                                          String contentType, String codeListIri, String codeListDataset,
                                          String entityName, String genderSuffix) {
        return new ConceptInputView(
                base.getDefiningLegalSource(), base.getRelatedLegalSource(),
                base.getDefiningNonLegalSource(), base.getRelatedNonLegalSource(),
                base.getExactMatch(), privacyProvisions, isPublic,
                agendaCode, agendaSystemCode, sharingMethod, acquisitionMethod, contentType,
                codeListIri, codeListDataset, entityName, genderSuffix);
    }
}
