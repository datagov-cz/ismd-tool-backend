package com.dia.ismdtoolbackend.service.snapshot;

import com.dia.ismdtoolbackend.controller.dto.LinkSnapshotDto;
import com.dia.ismdtoolbackend.controller.dto.NkdConceptRefDto;
import com.dia.ismdtoolbackend.entity.NkdConceptSnapshotEntity;
import com.dia.ismdtoolbackend.enums.SnapshotAction;
import com.dia.ismdtoolbackend.enums.SnapshotLinkType;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.DeviationStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@code GetOntologyDto.linkSnapshots} map from cached snapshot rows — a pure read, no NKD
 * call. Cold (never evaluated) or stale ({@code lastCheckedAt} older than the TTL) rows are surfaced
 * as {@link DeviationStatus#PENDING}; the caller fires the async warmer when {@link Result#needsWarming()}.
 */
public final class LinkSnapshotAssembler {

    private LinkSnapshotAssembler() {
    }

    /** The assembled map plus whether any row was cold/stale (⇒ caller should warm the graph). */
    public record Result(Map<String, List<LinkSnapshotDto>> byOwnerConcept, boolean needsWarming) {
    }

    public static Result assemble(List<NkdConceptSnapshotEntity> snapshots, Duration ttl, Instant now) {
        Map<String, List<LinkSnapshotDto>> byOwner = new LinkedHashMap<>();
        boolean needsWarming = false;

        for (NkdConceptSnapshotEntity s : snapshots) {
            boolean fresh = isFresh(s, ttl, now);
            if (!fresh) {
                needsWarming = true;
            }
            String ownerIri = s.getOwningConcept().getConceptIri();
            byOwner.computeIfAbsent(ownerIri, k -> new java.util.ArrayList<>()).add(toDto(s, fresh));
        }
        return new Result(byOwner, needsWarming);
    }

    private static boolean isFresh(NkdConceptSnapshotEntity s, Duration ttl, Instant now) {
        Instant checked = s.getLastCheckedAt();
        return checked != null && checked.isAfter(now.minus(ttl));
    }

    private static LinkSnapshotDto toDto(NkdConceptSnapshotEntity s, boolean fresh) {
        // Cold/stale → PENDING (warmer in flight); fresh → the cached deviation status.
        DeviationStatus status = fresh && s.getLastDeviationStatus() != null
                ? s.getLastDeviationStatus()
                : DeviationStatus.PENDING;

        return LinkSnapshotDto.builder()
                .snapshotId(s.getId())
                .owningConceptId(s.getOwningConcept().getId())
                .linkPredicate(SnapshotLinkType.fromValue(s.getLinkPredicate()).orElse(null))
                .origin(s.getOrigin())
                .nkdConcept(NkdConceptRefDto.builder()
                        .iri(s.getNkdIri())
                        .label(labelOf(s.getSnapshot()))
                        .build())
                .snapshotAt(s.getSnapshotAt())
                .lastCheckedAt(s.getLastCheckedAt())
                .status(status)
                // deviation block only meaningful when fresh; PENDING carries none.
                .deviation(fresh ? cachedDeviation(s) : null)
                .availableActions(actionsFor(status))
                .build();
    }

    /** Cached status as a minimal deviation envelope (the full per-field diff is recomputed on demand). */
    private static PublishedConceptDeviationModel cachedDeviation(NkdConceptSnapshotEntity s) {
        if (s.getLastDeviationStatus() == null) {
            return null;
        }
        return PublishedConceptDeviationModel.builder().status(s.getLastDeviationStatus()).build();
    }

    private static String labelOf(ConceptDetailModel detail) {
        if (detail == null || detail.getName() == null || detail.getName().isEmpty()) {
            return null;
        }
        // Prefer Czech, else any.
        String cs = detail.getName().get("cs");
        return cs != null ? cs : detail.getName().values().iterator().next();
    }

    private static List<SnapshotAction> actionsFor(DeviationStatus status) {
        return switch (status) {
            case HAS_DEVIATIONS -> List.of(SnapshotAction.UPDATE, SnapshotAction.REMOVE);
            case NO_DEVIATION -> List.of(SnapshotAction.REMOVE);
            // PENDING / ENDPOINT_UNAVAILABLE / QUERY_ERROR / CONCEPT_NOT_FOUND_IN_NKD → no action.
            default -> List.of();
        };
    }
}
