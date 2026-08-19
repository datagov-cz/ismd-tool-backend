package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.DiagramEdgeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Direct access to the persisted edge waypoints. An edge's existence, kind and endpoints are derived on
 * read from {@code live ⊕ overlay}, so a row here records only the geometry of the edge it is keyed to.
 */
public interface DiagramEdgeRepository extends JpaRepository<DiagramEdgeEntity, Long> {

    /** All persisted waypoint rows of a diagram (read-side assembly). */
    List<DiagramEdgeEntity> findByDiagramId(Long diagramId);
}
