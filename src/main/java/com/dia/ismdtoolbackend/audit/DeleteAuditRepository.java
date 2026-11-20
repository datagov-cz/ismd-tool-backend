package com.dia.ismdtoolbackend.audit;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/** Insert and read only, so the table stays append-only from the application. */
public interface DeleteAuditRepository extends Repository<DeleteAuditEntry, Long> {

    DeleteAuditEntry save(DeleteAuditEntry entry);

    /** Newest first; a null filter matches every row. */
    @Query(value = """
            SELECT * FROM ismd_schema.delete_audit
            WHERE (CAST(:entityType AS VARCHAR) IS NULL OR entity_type = CAST(:entityType AS VARCHAR))
              AND (CAST(:actorId AS VARCHAR) IS NULL OR actor_id = CAST(:actorId AS VARCHAR))
              AND (CAST(:fromTs AS TIMESTAMP WITH TIME ZONE) IS NULL OR deleted_at >= CAST(:fromTs AS TIMESTAMP WITH TIME ZONE))
              AND (CAST(:toTs AS TIMESTAMP WITH TIME ZONE) IS NULL OR deleted_at < CAST(:toTs AS TIMESTAMP WITH TIME ZONE))
            ORDER BY deleted_at DESC, id DESC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<DeleteAuditEntry> findPage(@Param("entityType") String entityType,
                                    @Param("actorId") String actorId,
                                    @Param("fromTs") Instant from,
                                    @Param("toTs") Instant to,
                                    @Param("limit") int limit,
                                    @Param("offset") int offset);

    @Query(value = """
            SELECT count(*) FROM ismd_schema.delete_audit
            WHERE (CAST(:entityType AS VARCHAR) IS NULL OR entity_type = CAST(:entityType AS VARCHAR))
              AND (CAST(:actorId AS VARCHAR) IS NULL OR actor_id = CAST(:actorId AS VARCHAR))
              AND (CAST(:fromTs AS TIMESTAMP WITH TIME ZONE) IS NULL OR deleted_at >= CAST(:fromTs AS TIMESTAMP WITH TIME ZONE))
              AND (CAST(:toTs AS TIMESTAMP WITH TIME ZONE) IS NULL OR deleted_at < CAST(:toTs AS TIMESTAMP WITH TIME ZONE))
            """, nativeQuery = true)
    long countMatching(@Param("entityType") String entityType,
                       @Param("actorId") String actorId,
                       @Param("fromTs") Instant from,
                       @Param("toTs") Instant to);
}
