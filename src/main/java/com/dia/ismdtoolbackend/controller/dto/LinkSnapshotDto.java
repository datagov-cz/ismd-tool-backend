package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.enums.SnapshotAction;
import com.dia.ismdtoolbackend.enums.SnapshotLinkType;
import com.dia.ismdtoolbackend.enums.SnapshotOrigin;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.DeviationStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * One tracked "local copy" of a published NKD concept that the viewed concept links to, surfaced on
 * the concept detail. Returned in {@code GetConceptDto.linkSnapshots} and as the body of the
 * local-copy UPDATE endpoint.
 *
 * <p><strong>Deviation semantics (the easy-to-get-wrong part).</strong> {@link #deviation} reuses
 * {@link PublishedConceptDeviationModel} unchanged, but for a {@link SnapshotOrigin#LINK_TARGET}
 * snapshot each {@code PropertyDeviation}'s {@code localValue} is the <em>stored local copy</em> and
 * {@code publishedValue} is <em>live NKD</em> — NOT "the user's own value". The FE must relabel the
 * columns keyed on {@link #origin} (e.g. "lokální kopie" / "NKD") rather than the SELF_PUBLISHED
 * labels ("vaše hodnota" / "publikováno"). The backend keeps a single comparison type; the FE owns
 * the label switch.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LinkSnapshotDto {

    /** Identifies the row for the action URLs (`/{conceptId}/localcopy/{snapshotId}[/update]`). */
    private Long snapshotId;

    /** Explicit owner id so the FE builds the action URLs without re-deriving from page context. */
    private Long owningConceptId;

    /**
     * The logical relation that created the link ({@code broaderClass} / {@code superProperty} /
     * {@code superRelation} / {@code exactMatch}) — drives the "linked as …" label.
     */
    private SnapshotLinkType linkPredicate;

    /**
     * Always {@link SnapshotOrigin#LINK_TARGET} this round, but carried explicitly so the FE relabels
     * the deviation pair (see class Javadoc) and stays forward-compatible when SELF_PUBLISHED arrives.
     */
    private SnapshotOrigin origin;

    /** The link target (NKD concept IRI + human label). */
    private NkdConceptRefDto nkdConcept;

    /** When the local copy was taken. */
    private Instant snapshotAt;

    /** When deviation was last evaluated — distinct from {@link #snapshotAt} (staleness signal). */
    private Instant lastCheckedAt;

    /** Hoisted from {@link #deviation} so the FE branches (badge / actions) without digging in. */
    private DeviationStatus status;

    /** The per-field diff block. See class Javadoc for the localValue/publishedValue semantics. */
    private PublishedConceptDeviationModel deviation;

    /** What the user may do: {@code HAS_DEVIATIONS}→[UPDATE,REMOVE]; {@code NO_DEVIATION}→[REMOVE]; error→[]. */
    private List<SnapshotAction> availableActions;
}
