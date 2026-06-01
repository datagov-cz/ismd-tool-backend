package com.dia.ismdtoolbackend.config.security;

import com.dia.ismdtoolbackend.service.security.OntologySecurityService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test implementation of OntologySecurityService for controller tests.
 */
@TestConfiguration
public class TestOntologySecurityService {

    private static final ThreadLocal<Boolean> ALLOW_MODIFY = ThreadLocal.withInitial(() -> true);

    public static void setAllowModify(boolean allow) {
        ALLOW_MODIFY.set(allow);
    }

    public static void reset() {
        ALLOW_MODIFY.set(true);
    }

    @Bean
    @Primary
    public OntologySecurityService ontologySecurityService() {
        return new OntologySecurityService(null, null, null) {
            @Override
            public boolean canModify(Long ontologyId) {
                return ALLOW_MODIFY.get();
            }

            @Override
            public boolean canModifyConcept(Long conceptId) {
                return ALLOW_MODIFY.get();
            }

            @Override
            public boolean canModifyComment(Long commentId) {
                return ALLOW_MODIFY.get();
            }

            @Override
            public boolean belongsToUserBySlug(String slug) {
                return ALLOW_MODIFY.get();
            }
        };
    }
}