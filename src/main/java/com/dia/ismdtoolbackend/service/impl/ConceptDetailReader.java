package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.config.NkdConfig;
import com.dia.ismdtoolbackend.controller.dto.GetConceptDto;
import com.dia.ismdtoolbackend.controller.dto.LinkSnapshotDto;
import com.dia.ismdtoolbackend.service.snapshot.LinkSnapshotAssembler;
import com.dia.ismdtoolbackend.service.snapshot.NkdSnapshotWarmer;
import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.repository.CommentRepository;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.service.NkdSnapshotService;
import com.dia.ismdtoolbackend.service.nkod.NkodCodelistService;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import com.dia.ismdtoolbackend.entity.NkdConceptSnapshotEntity;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Reads concepts: the metadata list, and one concept's detail with references, deviation and link snapshots. */
@Slf4j
@Component
@RequiredArgsConstructor
class ConceptDetailReader {

    private final ConceptMetadataRepository conceptMetadataRepository;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final ConceptMetadataMapper conceptMetadataMapper;
    private final CommentRepository commentRepository;
    private final OntologyDetailExtractor detailExtractor;
    private final ReferencedConceptsEnricher referencedConceptsEnricher;
    private final RppSnapshotHolder rppSnapshotHolder;
    private final NkodCodelistService nkodCodelistService;
    private final NkdSnapshotService nkdSnapshotService;
    private final NkdSnapshotWarmer nkdSnapshotWarmer;
    private final NkdConfig nkdConfig;
    private final com.dia.ismdtoolbackend.service.WorkingCopyDeviationService workingCopyDeviationService;

    List<ConceptMetadataModel> getAll(String userId, Boolean isPublished) {
        List<ConceptMetadataEntity> conceptMetadataEntities;

        if (userId != null && isPublished != null) {
            conceptMetadataEntities = conceptMetadataRepository.findAllByUserIdAndIsPublished(userId, isPublished);
        } else if (userId != null) {
            conceptMetadataEntities = conceptMetadataRepository.findAllByUserId(userId);
        } else if (isPublished != null) {
            conceptMetadataEntities = conceptMetadataRepository.findAllByIsPublished(isPublished);
        } else {
            conceptMetadataEntities = conceptMetadataRepository.findAll();
        }

        List<Long> conceptIds = conceptMetadataEntities.stream()
                .map(ConceptMetadataEntity::getId)
                .toList();
        Map<Long, List<CommentEntity>> commentsByConceptId = conceptIds.isEmpty()
                ? Map.of()
                : commentRepository.findByConceptMetadataIdIn(conceptIds).stream()
                        .collect(Collectors.groupingBy(c -> c.getConceptMetadata().getId()));

        return conceptMetadataEntities.stream()
                .map(entity -> {
                    ConceptMetadataModel model = conceptMetadataMapper.toDto(entity);
                    List<CommentEntity> commentEntities =
                            commentsByConceptId.getOrDefault(entity.getId(), List.of());
                    model.setComments(conceptMetadataMapper.commentEntitiesToModels(commentEntities));
                    return model;
                })
                .toList();
    }

    GetConceptDto getConceptDetail(String conceptSlug) {
        Optional<ConceptMetadataEntity> conceptMetadataOpt = conceptMetadataRepository.findBySlug(conceptSlug);
        if (conceptMetadataOpt.isEmpty()) {
            log.error("conceptSlug {} not found", conceptSlug);
            throw new OntologyException("Metadata pojmu s názvem " + conceptSlug + " nebyla nalezena.");
        }

        ConceptMetadataEntity metadataEntity = conceptMetadataOpt.get();
        String graphName = metadataEntity.getGraphName();
        String conceptIri = metadataEntity.getConceptIri();

        Model rawModel = jenaTDB2Repository.fetchGraph(graphName);

        if (rawModel.isEmpty()) {
            log.error("Ontology model is empty for graph: {}", graphName);
            throw new OntologyException("Slovník je prázdný, nebo nebyl nalezen.");
        }

        // The canonical deviation projection is defined over this graph ALONE, so it is computed here,
        // before the cross-graph merge below mutates rawModel in place. Doing it in this order saves
        // both a second Fuseki read of the same graph and a defensive copy of it.
        OntologyDetailModel.ConceptDetailModel canonicalLocal = Boolean.TRUE.equals(metadataEntity.getIsPublished())
                ? workingCopyDeviationService.canonicalLocalConcept(conceptIri, rawModel)
                : null;

        // The class-detail read traverses only this concept's own graph, so a
        // property/relationship whose rdfs:domain points here but which lives in a
        // different vocabulary graph would be dropped (it is still visible from the
        // property's own detail). Merge those cross-graph members in so the class
        // shows them too. Additive only — safe for the downstream deviation check.
        rawModel.add(jenaTDB2Repository.fetchExternalDomainMembers(conceptIri));

        OntologyDetailModel.ConceptDetailModel conceptDetail = detailExtractor.extractConceptDetail(rawModel, conceptIri);

        if (conceptDetail == null) {
            log.error("Concept detail not found for IRI: {}", conceptIri);
            throw new OntologyException("Detail pojmu s IRI " + conceptIri + " nebyl nalezen.");
        }

        referencedConceptsEnricher.enrich(conceptDetail);
        resolveRppReferences(conceptDetail);
        resolveCodeList(conceptDetail);

        ConceptMetadataModel metadataModel = conceptMetadataMapper.toDto(metadataEntity);

        List<CommentEntity> commentEntities = commentRepository.findByConceptMetadataId(metadataEntity.getId());
        metadataModel.setComments(conceptMetadataMapper.commentEntitiesToModels(commentEntities));

        GetConceptDto result = new GetConceptDto();
        result.setConceptMetadata(metadataModel);
        result.setConceptDetail(conceptDetail);

        PublishedConceptDeviationModel conceptDeviation = checkPublishedConcept(metadataModel, canonicalLocal);
        result.setPublishedConceptDeviationModel(conceptDeviation);

        surfaceLinkSnapshots(result, metadataEntity);

        return result;
    }

    void resolveRppReferences(OntologyDetailModel.ConceptDetailModel detail) {
        String agendaIri = detail.getAgenda();
        if (agendaIri != null) {
            rppSnapshotHolder.findAgendaByIri(agendaIri).ifPresent(detail::setAgendaResolved);
        }
        String aisIri = detail.getAis();
        if (aisIri != null) {
            rppSnapshotHolder.findIsvsByIri(aisIri).ifPresent(detail::setAisResolved);
        }
    }

    void resolveCodeList(OntologyDetailModel.ConceptDetailModel detail) {
        nkodCodelistService.check(detail.getCodeList()).ifPresent(detail::setCodeListResolved);
    }

    /**
     * Surfaces this concept's tracked local copies (and their {@code snapshotId}, which the UPDATE/REMOVE
     * action URLs need) on concept detail, mirroring the ontology-detail path: the snapshot row IS the
     * cache, so this never calls NKD; cold/stale rows surface as {@code PENDING} and fire the async warmer.
     *
     * <p>The read never writes and never breaks the detail — a snapshot problem must not cost the user
     * their concept page.
     */
    void surfaceLinkSnapshots(GetConceptDto result, ConceptMetadataEntity metadata) {
        try {
            List<NkdConceptSnapshotEntity> rows = nkdSnapshotService.findForConcept(metadata.getId());
            if (rows.isEmpty()) {
                return;
            }
            LinkSnapshotAssembler.Result assembled = LinkSnapshotAssembler.assemble(
                    rows, nkdConfig.getSnapshot().getDeviationTtl(), Instant.now());

            List<LinkSnapshotDto> forThisConcept = assembled.byOwnerConcept()
                    .getOrDefault(metadata.getConceptIri(), List.of());
            if (!forThisConcept.isEmpty()) {
                result.setLinkSnapshots(forThisConcept);
            }
            if (assembled.needsWarming()) {
                nkdSnapshotWarmer.warmGraph(metadata.getGraphName());
            }
        } catch (Exception e) {
            log.warn("Failed to surface link snapshots for concept {}: {}",
                    metadata.getConceptIri(), e.getMessage());
        }
    }

    /**
     * @param canonicalLocal the canonical local projection when the caller already computed it off a
     *                       graph it had in hand; {@code null} makes the deviation service read it
     */
    PublishedConceptDeviationModel checkPublishedConcept(ConceptMetadataModel conceptMetadata,
                                                                 OntologyDetailModel.ConceptDetailModel canonicalLocal) {
        if (Boolean.FALSE.equals(conceptMetadata.getIsPublished())) {
            return null;
        }
        return workingCopyDeviationService.deviationForWithLocal(
                conceptMetadata.getConceptIri(), canonicalLocal);
    }
}
