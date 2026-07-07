package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.DiagramEdgeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Direct edge access. Persisted edges are diagram-owned only (draft links); real-concept edges are derived
 * on read. Deleting a draft node must also drop the edges touching it — these finders back that cleanup.
 */
public interface DiagramEdgeRepository extends JpaRepository<DiagramEdgeEntity, Long> {

    /** All persisted edges of a diagram (read-side assembly). */
    List<DiagramEdgeEntity> findByDiagramId(Long diagramId);

    /** Edges whose source or target is a given node — the set to remove when that node is deleted. */
    List<DiagramEdgeEntity> findBySourceNodeIdOrTargetNodeId(Long sourceNodeId, Long targetNodeId);
}