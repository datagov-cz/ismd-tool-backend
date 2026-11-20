package com.dia.ismdtoolbackend.audit;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.utility.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Records a delete in the caller's transaction, so the audit row commits or rolls back with the delete itself.
 * The actor is the authenticated user of the request.
 */
@Component
@RequiredArgsConstructor
public class DeleteAuditWriter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DeleteAuditRepository repository;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void ontology(Long id, String slug, String graphName, long concepts, long diagrams, long comments) {
        write(DeletedEntityType.ONTOLOGY, id, graphName, slug, null, graphName, null,
                Map.of("concepts", concepts, "diagrams", diagrams, "comments", comments));
    }

    /** {@code cascadedConceptIris}: the related concepts deleted with it, without the concept itself. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void concept(Long id, String iri, String slug, String name, String graphName,
                        List<String> cascadedConceptIris) {
        write(DeletedEntityType.CONCEPT, id, iri, slug, name, graphName, null,
                cascadedConceptIris.isEmpty() ? null : Map.of("cascadedConcepts", cascadedConceptIris));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void localCopy(Long snapshotId, String nkdIri, String graphName, String owningConceptIri) {
        write(DeletedEntityType.LOCAL_COPY, snapshotId, nkdIri, null, null, graphName, owningConceptIri, null);
    }

    /** The comment text is not recorded. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void comment(Long id, String graphName, String conceptIri) {
        write(DeletedEntityType.COMMENT, id, null, null, null, graphName, conceptIri, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void diagram(Long id, String name, String ontologySlug, String graphName) {
        write(DeletedEntityType.DIAGRAM, id, null, ontologySlug, name, graphName, null, null);
    }

    private void write(DeletedEntityType type, Long id, String iri, String slug, String name, String graphName,
                       String parentIri, Map<String, ?> details) {
        SecurityUser actor = SecurityUtils.getCurrentUser();
        DeleteAuditEntry entry = new DeleteAuditEntry();
        entry.setDeletedAt(Instant.now(clock));
        entry.setActorId(actor.getUserId());
        entry.setActorAdmin(actor.isAdmin());
        entry.setEntityType(type);
        entry.setEntityId(id);
        entry.setEntityIri(iri);
        entry.setEntitySlug(slug);
        entry.setEntityName(name);
        entry.setGraphName(graphName);
        entry.setParentIri(parentIri);
        entry.setDetails(details == null ? null : OBJECT_MAPPER.writeValueAsString(details));
        repository.save(entry);
    }
}
