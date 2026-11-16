package com.dia.ismdtoolbackend.service.security;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.repository.CommentRepository;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OntologySecurityServiceTest {

    private static final String OWNER = "owner-uuid";
    private static final String OTHER = "other-uuid";

    @Mock
    private OntologyMetadataRepository ontologies;
    @Mock
    private ConceptMetadataRepository concepts;
    @Mock
    private CommentRepository comments;

    private OntologySecurityService service;

    /** Every ownership check, with how to stub its lookup and the message it throws when missing. */
    enum Check {
        ONTOLOGY_BY_ID("Slovník s id 7 nebyl nalezen.") {
            void stub(OntologySecurityServiceTest t, Optional<String> owner) {
                when(t.ontologies.findById(7L)).thenReturn(owner.map(OntologySecurityServiceTest::ontologyOwnedBy));
            }
            boolean call(OntologySecurityService s) {
                return s.canModify(7L);
            }
        },
        ONTOLOGY_BY_IRI("Slovník s IRI https://example.org/slovnik nebyl nalezen.") {
            void stub(OntologySecurityServiceTest t, Optional<String> owner) {
                when(t.ontologies.findByGraphName("https://example.org/slovnik"))
                        .thenReturn(owner.map(OntologySecurityServiceTest::ontologyOwnedBy));
            }
            boolean call(OntologySecurityService s) {
                return s.canModify("https://example.org/slovnik");
            }
        },
        ONTOLOGY_BY_SLUG("Slovník se slugem muj-slovnik nebyl nalezen.") {
            void stub(OntologySecurityServiceTest t, Optional<String> owner) {
                when(t.ontologies.findBySlug("muj-slovnik")).thenReturn(owner.map(OntologySecurityServiceTest::ontologyOwnedBy));
            }
            boolean call(OntologySecurityService s) {
                return s.belongsToUserBySlug("muj-slovnik");
            }
        },
        CONCEPT("Pojem s id 8 nebyl nalezen.") {
            void stub(OntologySecurityServiceTest t, Optional<String> owner) {
                when(t.concepts.findById(8L)).thenReturn(owner.map(id -> {
                    ConceptMetadataEntity e = new ConceptMetadataEntity();
                    e.setUserId(id.isEmpty() ? null : id);
                    return e;
                }));
            }
            boolean call(OntologySecurityService s) {
                return s.canModifyConcept(8L);
            }
        },
        COMMENT("Komentář s id 9 nebyl nalezen.") {
            void stub(OntologySecurityServiceTest t, Optional<String> owner) {
                when(t.comments.findById(9L)).thenReturn(owner.map(id -> {
                    CommentEntity e = new CommentEntity();
                    e.setUserId(id.isEmpty() ? null : id);
                    return e;
                }));
            }
            boolean call(OntologySecurityService s) {
                return s.canModifyComment(9L);
            }
        };

        final String notFoundMessage;

        Check(String notFoundMessage) {
            this.notFoundMessage = notFoundMessage;
        }

        /** {@code owner} empty = no such resource; {@code ""} = resource with no owner. */
        abstract void stub(OntologySecurityServiceTest t, Optional<String> owner);

        abstract boolean call(OntologySecurityService s);
    }

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        service = new OntologySecurityService(ontologies, concepts, comments);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @EnumSource(Check.class)
    void ownerMayModify(Check check) {
        signIn(OWNER);
        check.stub(this, Optional.of(OWNER));

        assertThat(check.call(service)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(Check.class)
    void anotherUserMayNotModify(Check check) {
        signIn(OTHER);
        check.stub(this, Optional.of(OWNER));

        assertThat(check.call(service)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Check.class)
    void resourceWithoutOwnerIsDeniedInsteadOfFailing(Check check) {
        signIn(OTHER);
        check.stub(this, Optional.of(""));

        assertThat(check.call(service)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Check.class)
    void userWithoutIdDoesNotOwnAResourceWithoutOwner(Check check) {
        signIn(null);
        check.stub(this, Optional.of(""));

        assertThat(check.call(service)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Check.class)
    void adminMayModifyWithoutLookingUpTheResource(Check check) {
        signIn(OTHER, "ROLE_ADMIN");

        assertThat(check.call(service)).isTrue();
        verifyNoInteractions(ontologies, concepts, comments);
    }

    @ParameterizedTest
    @EnumSource(Check.class)
    void missingResourceIsNotFound(Check check) {
        signIn(OWNER);
        check.stub(this, Optional.empty());

        assertThatThrownBy(() -> check.call(service))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage(check.notFoundMessage);
    }

    @ParameterizedTest
    @EnumSource(Check.class)
    void anonymousCallerIsRejected(Check check) {
        assertThatThrownBy(() -> check.call(service))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("User not authenticated");
        verifyNoInteractions(ontologies, concepts, comments);
    }

    @Test
    void authenticatedUserMayViewResources() {
        signIn(OWNER);

        assertThat(service.canViewResource()).isTrue();
    }

    @Test
    void anonymousCallerMayNotViewResources() {
        assertThatThrownBy(service::canViewResource).isInstanceOf(IllegalStateException.class);
    }

    private static void signIn(String userId, String... roles) {
        SecurityUser user = new SecurityUser(userId, "Uživatel", List.of(roles));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    private static OntologyMetadataEntity ontologyOwnedBy(String userId) {
        OntologyMetadataEntity e = new OntologyMetadataEntity();
        e.setUserId(userId.isEmpty() ? null : userId);
        return e;
    }
}
