package com.dia.ismdtoolbackend.config.security;

import org.springframework.security.test.context.support.WithSecurityContext;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Annotation to inject a mock SecurityUser into the Spring Security context for testing.
 * <p>
 * This is the test equivalent of having a real JWT-authenticated user.
 * Use this annotation on test methods or test classes to simulate authenticated users.
 * <p>
 * Example usage:
 * <pre>
 * {@literal @}Test
 * {@literal @}WithMockSecurityUser(userId = "user123", roles = {"ROLE_USER"})
 * void testCreateConcept_Success() {
 *     // Test code - SecurityUser will be available via @AuthenticationPrincipal
 * }
 * </pre>
 * <p>
 * Admin user example:
 * <pre>
 * {@literal @}Test
 * {@literal @}WithMockSecurityUser(userId = "admin", roles = {"ROLE_ADMIN"})
 * void testDeleteOntology_AsAdmin() {
 *     // Test code with admin privileges
 * }
 * </pre>
 *
 * @see WithMockSecurityUserSecurityContextFactory
 */
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithMockSecurityUserSecurityContextFactory.class)
public @interface WithMockSecurityUser {

    String userId() default "testUser";

    String displayName() default "testUser";

    String[] roles() default {"ROLE_USER"};
}