package com.dia.ismdtoolbackend.testsupport.web;

import com.dia.ismdtoolbackend.service.security.OntologySecurityService;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * {@code ontologySecurityService} for controller slices. The ownership checks ({@code canModify(Long)},
 * {@code canModifyConcept}, {@code canModifyComment}, {@code belongsToUserBySlug}) answer one flag that
 * allows by default; {@code canViewResource} and {@code canModify(String)} run the real code.
 *
 * <p>The flag lives in the context's bean and {@link ResetBeforeEach} sets it back to allow before every
 * test, so a deny cannot leak into the next test or class.
 */
public class StubOntologySecurityService extends OntologySecurityService {

    private volatile boolean allowModify = true;

    public StubOntologySecurityService() {
        super(null, null, null);
    }

    /** Every ownership check answers {@code false} for the rest of the current test. */
    public void denyModify() {
        allowModify = false;
    }

    void allowAll() {
        allowModify = true;
    }

    @Override
    public boolean canModify(Long ontologyId) {
        return allowModify;
    }

    @Override
    public boolean canModifyConcept(Long conceptId) {
        return allowModify;
    }

    @Override
    public boolean canModifyComment(Long commentId) {
        return allowModify;
    }

    @Override
    public boolean belongsToUserBySlug(String slug) {
        return allowModify;
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        public StubOntologySecurityService ontologySecurityService() {
            return new StubOntologySecurityService();
        }
    }

    /** Sets the context's stub back to allow before each test. */
    public static class ResetBeforeEach implements BeforeEachCallback {

        @Override
        public void beforeEach(ExtensionContext context) {
            SpringExtension.getApplicationContext(context)
                    .getBeanProvider(StubOntologySecurityService.class)
                    .ifAvailable(StubOntologySecurityService::allowAll);
        }
    }
}