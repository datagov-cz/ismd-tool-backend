package com.dia.ismdtoolbackend.service.security;

import com.dia.ismdtoolbackend.exception.CommentNotFoundException;
import com.dia.ismdtoolbackend.exception.ConceptNotFoundException;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.repository.CommentRepository;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.utility.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Authorization checks used by {@code @PreAuthorize}.
 * <p>
 * An admin may modify anything. Anyone else may modify a resource only when they own it. A
 * resource with no owner belongs to nobody, so only an admin may modify it. A missing resource
 * throws its not-found exception (404); admins are allowed before the lookup.
 */
@Slf4j
@Service("ontologySecurityService")
@RequiredArgsConstructor
public class OntologySecurityService {

    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final CommentRepository commentRepository;

    public boolean canModify(Long ontologyId) {
        return isAdminOrOwner("ontology", ontologyId,
                () -> ownerOf(ontologyMetadataRepository.findById(ontologyId), OntologyMetadataEntity::getUserId,
                        "Slovník s id " + ontologyId + " nebyl nalezen.", OntologyNotFoundException::new));
    }

    public boolean canModify(String ontologyIri) {
        return isAdminOrOwner("ontology", ontologyIri,
                () -> ownerOf(ontologyMetadataRepository.findByGraphName(ontologyIri), OntologyMetadataEntity::getUserId,
                        "Slovník s IRI " + ontologyIri + " nebyl nalezen.", OntologyNotFoundException::new));
    }

    public boolean canModifyConcept(Long conceptId) {
        return isAdminOrOwner("concept", conceptId,
                () -> ownerOf(conceptMetadataRepository.findById(conceptId), ConceptMetadataEntity::getUserId,
                        "Pojem s id " + conceptId + " nebyl nalezen.", ConceptNotFoundException::new));
    }

    public boolean canModifyComment(Long commentId) {
        return isAdminOrOwner("comment", commentId,
                () -> ownerOf(commentRepository.findById(commentId), CommentEntity::getUserId,
                        "Komentář s id " + commentId + " nebyl nalezen.", CommentNotFoundException::new));
    }

    public boolean belongsToUserBySlug(String slug) {
        return isAdminOrOwner("ontology", slug,
                () -> ownerOf(ontologyMetadataRepository.findBySlug(slug), OntologyMetadataEntity::getUserId,
                        "Slovník se slugem " + slug + " nebyl nalezen.", OntologyNotFoundException::new));
    }

    public boolean canViewResource() {
        SecurityUser currentUser = SecurityUtils.getCurrentUser();
        if (currentUser.isCredentialsNonExpired()) {
            return true;
        }
        log.warn("User {} credentials expired", currentUser.getUserId());
        return false;
    }

    private boolean isAdminOrOwner(String kind, Object resource, Supplier<String> ownerLookup) {
        SecurityUser currentUser = SecurityUtils.getCurrentUser();
        if (currentUser.isAdmin()) {
            log.debug("User {} is admin - modify permitted for {} {}", currentUser.getUserId(), kind, resource);
            return true;
        }

        String ownerId = ownerLookup.get();
        boolean isOwner = ownerId != null && ownerId.equals(currentUser.getUserId());
        if (isOwner) {
            log.debug("User {} is owner - modify permitted for {} {}", currentUser.getUserId(), kind, resource);
        } else {
            log.warn("User {} attempted to modify {} {} owned by {}", currentUser.getUserId(), kind, resource, ownerId);
        }
        return isOwner;
    }

    private static <T> String ownerOf(Optional<T> entity, Function<T, String> owner, String notFoundMessage,
                                      Function<String, RuntimeException> notFound) {
        T found = entity.orElseThrow(() -> {
            log.error(notFoundMessage);
            return notFound.apply(notFoundMessage);
        });
        return owner.apply(found);
    }
}
