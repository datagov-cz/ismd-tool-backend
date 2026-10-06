package com.dia.ismdtoolbackend.service.security;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.repository.CommentRepository;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.utility.security.SecurityUtils;
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
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final CommentRepository commentRepository;

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

    public boolean canModifyConcept(Long conceptId) {
        log.debug("Checking modify permission for concept: {}", conceptId);

        // Get current authenticated user
        SecurityUser currentUser = SecurityUtils.getCurrentUser();

        // Admin can modify anything
        if (currentUser.isAdmin()) {
            log.debug("User {} is admin - modify permitted for concept {}", currentUser.getUserId(), conceptId);
            return true;
        }

        // Non-admin: check ownership
        ConceptMetadataEntity entity = conceptMetadataRepository.findById(conceptId).orElseThrow(() -> {
            log.error("Concept not found: {}", conceptId);
            return new EntityNotFoundException("Pojem s id " + conceptId + " nebyl nalezen.");
        });

        boolean isOwner = entity.getUserId().equals(currentUser.getUserId());

        if (isOwner) {
            log.debug("User {} is owner - modify permitted for concept {}", currentUser.getUserId(), conceptId);
        } else {
            log.warn("User {} attempted to modify concept {} owned by {}", currentUser.getUserId(), conceptId, entity.getUserId());
        }

        return isOwner;
    }

    public boolean canModifyComment(Long commentId) {
        log.debug("Checking modify permission for comment: {}", commentId);

        // Get current authenticated user
        SecurityUser currentUser = SecurityUtils.getCurrentUser();

        // Admin can modify anything
        if (currentUser.isAdmin()) {
            log.debug("User {} is admin - modify permitted for comment {}", currentUser.getUserId(), commentId);
            return true;
        }

        // Non-admin: check ownership
        CommentEntity entity = commentRepository.findById(commentId).orElseThrow(() -> {
            log.error("comment not found: {}", commentId);
            return new EntityNotFoundException("Komentář s id " + commentId + " nebyl nalezen.");
        });

        boolean isOwner = entity.getUserId().equals(currentUser.getUserId());

        if (isOwner) {
            log.debug("User {} is owner - modify permitted for comment {}", currentUser.getUserId(), commentId);
        } else {
            log.warn("User {} attempted to modify comment {} owned by {}", currentUser.getUserId(), commentId, entity.getUserId());
        }

        return isOwner;
    }

    public boolean belongsToUserBySlug(String slug) {
        log.debug("Checking modify permission for ontology: {}", slug);

        SecurityUser currentUser = SecurityUtils.getCurrentUser();

        if (currentUser.isAdmin()) {
            log.debug("User {} is admin - modify permitted for ontology {}", currentUser.getUserId(), slug);
            return true;
        }

        OntologyMetadataEntity entity = ontologyMetadataRepository.findBySlug(slug).orElseThrow(() -> {
            log.error("Ontology not found: {}", slug);
            return new EntityNotFoundException("Slovník s IRI " + slug + " nebyl nalezen.");
        });

        boolean isOwner = entity.getUserId().equals(currentUser.getUserId());

        if (isOwner) {
            log.debug("User {} is owner - modify permitted for ontology {}", currentUser.getUserId(), slug);
        } else {
            log.warn("User {} attempted to modify ontology {} owned by {}", currentUser.getUserId(), slug, entity.getUserId());
        }

        return isOwner;
    }

    public boolean canViewResource() {
        log.debug("Checking credentials not expired for user");

        SecurityUser currentUser = SecurityUtils.getCurrentUser();
        if (currentUser.isCredentialsNonExpired()) {
            log.debug("User {} credentials valid", currentUser.getUserId());
            return true;
        }

        log.warn("User {} credentials expired", currentUser.getUserId());
        return false;
    }
}
