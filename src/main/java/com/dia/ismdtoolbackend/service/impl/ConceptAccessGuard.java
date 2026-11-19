package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.utility.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import org.springframework.security.access.AccessDeniedException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Loads a concept's metadata row and asserts the caller may modify it. */
@Slf4j
@Component
@RequiredArgsConstructor
class ConceptAccessGuard {

    private final ConceptMetadataRepository conceptMetadataRepository;

    /**
     * Defence in depth: the caller may only edit/delete a concept it owns. {@code ConceptController} already
     * gates both operations with {@code @PreAuthorize canModifyConcept}, but that check is controller-resident
     * — a service-to-service caller (the diagram layer is the first) inherits none of it. Asserting here means
     * the guarantee holds at the layer that actually performs the write.
     *
     * <p>Enforced only when a request context is present. A caller running outside one (the outbox relay, a
     * scheduler, a warmer) has no user to check and is trusted by construction; requiring authentication here
     * would break those paths instead of protecting them.
     */
    void assertCanModify(Long conceptId) {
        SecurityUser currentUser;
        try {
            currentUser = SecurityUtils.getCurrentUser();
        } catch (IllegalStateException e) {
            return;   // no authenticated context — a non-request caller, not a cross-tenant reach
        }
        if (currentUser.isAdmin()) {
            return;
        }
        ConceptMetadataEntity metadata = fetchAndValidateMetadata(conceptId);
        if (!Objects.equals(metadata.getUserId(), currentUser.getUserId())) {
            log.warn("User {} attempted to modify concept {} owned by {}",
                    currentUser.getUserId(), conceptId, metadata.getUserId());
            throw new AccessDeniedException("Nemáte oprávnění upravovat tento pojem.");
        }
    }

    ConceptMetadataEntity fetchAndValidateMetadata(Long conceptId) {
        return fetchAndValidateMetadata(conceptId, false);
    }

    ConceptMetadataEntity fetchAndValidateMetadata(Long conceptId, boolean lock) {
        Optional<ConceptMetadataEntity> metadataOpt = lock
                ? conceptMetadataRepository.findWithLockById(conceptId)
                : conceptMetadataRepository.findById(conceptId);
        if (metadataOpt.isEmpty()) {
            log.error("Concept metadata not found for ID: {}", conceptId);
            throw new OntologyException("Metadata pojmu s ID " + conceptId + " nebyla nalezena.");
        }
        return metadataOpt.get();
    }
}
