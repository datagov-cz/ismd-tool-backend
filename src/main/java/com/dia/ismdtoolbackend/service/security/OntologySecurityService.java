package com.dia.ismdtoolbackend.service.security;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.utils.SecurityUtils;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Security service for ontology authorization checks.
 * Used by @PreAuthorize annotations to verify user permissions.
 */
@Slf4j
@Service("ontologySecurityService")
@RequiredArgsConstructor
public class OntologySecurityService {

    private final OntologyMetadataRepository ontologyMetadataRepository;

    /**
     * Checks if the current authenticated user can update or delete the specified ontology.
     * <p>
     * Authorization rules:
     * - Admin users (ROLE_ADMIN): can delete any ontology
     * - Regular users: can only update and delete their own ontologies
     *
     * @param ontologyId ID of the ontology to check
     * @return true if user can update or delete, false otherwise
     * @throws EntityNotFoundException if ontology does not exist
     */
    public boolean canModify(Long ontologyId) {
        log.debug("Checking modify permission for ontology: {}", ontologyId);

        // Get current authenticated user
        SecurityUser currentUser = SecurityUtils.getCurrentUser();

        // Admin can modify anything
        if (currentUser.isAdmin()) {
            log.debug("User {} is admin - modify permitted for ontology {}", currentUser.getUserId(), ontologyId);
            return true;
        }

        // Non-admin: check ownership
        OntologyMetadataEntity entity = ontologyMetadataRepository.findById(ontologyId).orElseThrow(() -> {
            log.error("Ontology not found: {}", ontologyId);
            return new EntityNotFoundException("Slovník s id " + ontologyId + " nebyl nalezen.");
        });

        boolean isOwner = entity.getUserId().equals(currentUser.getUserId());

        if (isOwner) {
            log.debug("User {} is owner - modify permitted for ontology {}", currentUser.getUserId(), ontologyId);
        } else {
            log.warn("User {} attempted to modify ontology {} owned by {}", currentUser.getUserId(), ontologyId, entity.getUserId());
        }

        return isOwner;
    }

    public boolean canModify(String ontologyIRI) {
        log.debug("Checking modify permission for ontology: {}", ontologyIRI);

        SecurityUser currentUser = SecurityUtils.getCurrentUser();

        if (currentUser.isAdmin()) {
            log.debug("User {} is admin - modify permitted for ontology {}", currentUser.getUserId(), ontologyIRI);
            return true;
        }

        OntologyMetadataEntity entity = ontologyMetadataRepository.findByGraphName(ontologyIRI).orElseThrow(() -> {
            log.error("Ontology not found: {}", ontologyIRI);
            return new EntityNotFoundException("Slovník s IRI " + ontologyIRI + " nebyl nalezen.");
        });

        boolean isOwner = entity.getUserId().equals(currentUser.getUserId());

        if (isOwner) {
            log.debug("User {} is owner - modify permitted for ontology {}", currentUser.getUserId(), ontologyIRI);
        } else {
            log.warn("User {} attempted to modify ontology {} owned by {}", currentUser.getUserId(), ontologyIRI, entity.getUserId());
        }

        return isOwner;
    }
}
