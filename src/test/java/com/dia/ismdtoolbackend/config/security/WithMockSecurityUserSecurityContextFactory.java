package com.dia.ismdtoolbackend.config.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Factory that creates SecurityContext with mock SecurityUser for @WithMockSecurityUser annotation.
 * <p>
 * This class is invoked automatically by Spring Security Test framework when it encounters
 * {@link WithMockSecurityUser} annotation on a test method or test class.
 * <p>
 * The factory:
 * 1. Extracts userId and roles from the annotation
 * 2. Creates a SecurityUser with those attributes
 * 3. Wraps it in an Authentication object
 * 4. Sets up the SecurityContext
 * <p>
 * This simulates what {@link JwtAuthenticationConverter} does in production when processing
 * a real JWT token from Keycloak.
 *
 * @see WithMockSecurityUser
 * @see SecurityUser
 */
public class WithMockSecurityUserSecurityContextFactory
        implements WithSecurityContextFactory<WithMockSecurityUser> {

    @Override
    public SecurityContext createSecurityContext(WithMockSecurityUser annotation) {
        String userId = annotation.userId();
        List<String> roles = Arrays.asList(annotation.roles());

        SecurityUser securityUser = new SecurityUser(userId, roles);

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                securityUser,
                null,
                securityUser.getAuthorities()
        );

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);

        return context;
    }
}