package com.dia.ismdtoolbackend.testsupport.web;

import org.springframework.security.test.context.support.WithSecurityContext;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Authenticates the test with a Keycloak-shaped {@code Jwt} passed through the production
 * {@code JwtAuthenticationConverter}, so the principal is a {@code SecurityUser} and the credentials are
 * the {@code Jwt} whose token value controllers forward downstream.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@WithSecurityContext(factory = WithMockJwtSecurityContextFactory.class)
public @interface WithMockJwt {

    /** The raw token value, as the frontend sends it in {@code Authorization: Bearer}. */
    String tokenValue() default "raw.jwt.from.frontend";

    /** The {@code sub} claim, which becomes the user id. */
    String subject() default "user-123";

    /** The {@code realm_access.roles} claim. */
    String[] realmRoles() default {"user"};
}
