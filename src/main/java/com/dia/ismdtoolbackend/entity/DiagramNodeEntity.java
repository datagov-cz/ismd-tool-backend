package com.dia.ismdtoolbackend.entity;

import com.dia.ismdtoolbackend.enums.DiagramNodeBacking;
import com.dia.ismdtoolbackend.models.diagram.DiagramDraftContent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * One node on a diagram canvas — either a reference to a real ISMD concept or a draft sketch.
 *
 * <p>The {@link #backing} decides which: an {@link DiagramNodeBacking#ISMD_CONCEPT} node carries a
 * {@link #conceptIri} and no content (joined live to PG/RDF on read); a {@link DiagramNodeBacking#DRAFT}
 * node carries {@link #draftContent} (its {@code draftJson}) and no IRI. This is the only place the
 * diagram layer holds concept content, and only for drafts.
 *
 * <p>Position and grouping are presentation state; {@code collapsed}/{@code hidden} are the subset of UI
 * flags worth persisting (transient ReactFlow state — {@code selected}, {@code dragging}, {@code measured}
 * — is stripped by the FE and never stored).
 */
@Entity
@Table(name = "diagram_nodes")
@NoArgsConstructor
@Getter
@Setter
@Slf4j
public class DiagramNodeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "diagram_id", nullable = false)
    private DiagramEntity diagram;

    @Column(name = "backing", nullable = false)
    @Enumerated(EnumType.STRING)
    private DiagramNodeBacking backing;

    /**
     * The referenced concept's IRI — set for {@link DiagramNodeBacking#ISMD_CONCEPT}, null for drafts.
     */
    @Column(name = "concept_iri", length = 1024)
    private String conceptIri;

    @Column(name = "pos_x", nullable = false)
    private Double posX;

    @Column(name = "pos_y", nullable = false)
    private Double posY;

    @Column(name = "collapsed", nullable = false)
    private boolean collapsed = false;

    @Column(name = "hidden", nullable = false)
    private boolean hidden = false;

    /** Parent node id for grouping/containers (ReactFlow {@code parentId}); null for top-level nodes. */
    @Column(name = "parent_node_id")
    private Long parentNodeId;

    /**
     * Serialized {@link DiagramDraftContent} — the draft's concept-shaped content. Set only for
     * {@link DiagramNodeBacking#DRAFT} nodes; null for concept references.
     */
    @Column(name = "draft_json", columnDefinition = "text")
    private String draftJson;

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Deserialize the draft content. Returns {@code null} on absent/malformed JSON (logged). */
    public DiagramDraftContent getDraftContent() {
        if (draftJson == null || draftJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(draftJson, DiagramDraftContent.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize draft JSON for diagram node id={}", id, e);
            return null;
        }
    }

    /** Serialize and store the draft content. A null value clears the column. */
    public void setDraftContent(DiagramDraftContent content) {
        if (content == null) {
            this.draftJson = null;
            return;
        }
        try {
            this.draftJson = objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize draft content for diagram node id={}", id, e);
            this.draftJson = null;
        }
    }
}