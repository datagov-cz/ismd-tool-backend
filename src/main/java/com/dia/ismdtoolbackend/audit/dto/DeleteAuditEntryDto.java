package com.dia.ismdtoolbackend.audit.dto;

import com.dia.ismdtoolbackend.audit.DeleteAuditEntry;
import com.dia.ismdtoolbackend.audit.DeletedEntityType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** One recorded delete; {@code details} is the stored JSON text. */
public record DeleteAuditEntryDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant deletedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String actorId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean actorAdmin,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DeletedEntityType entityType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long entityId,
        String entityIri,
        String entitySlug,
        String entityName,
        String graphName,
        String parentIri,
        String details
) {
    public static DeleteAuditEntryDto of(DeleteAuditEntry e) {
        return new DeleteAuditEntryDto(e.getId(), e.getDeletedAt(), e.getActorId(), e.isActorAdmin(),
                e.getEntityType(), e.getEntityId(), e.getEntityIri(), e.getEntitySlug(), e.getEntityName(),
                e.getGraphName(), e.getParentIri(), e.getDetails());
    }
}
