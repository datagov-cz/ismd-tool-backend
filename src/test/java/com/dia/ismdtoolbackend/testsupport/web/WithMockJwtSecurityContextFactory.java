package com.dia.ismdtoolbackend.testsupport.web;

import com.dia.ismdtoolbackend.config.security.JwtAuthenticationConverter;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import java.util.List;
import java.util.Map;

/** Builds the {@link WithMockJwt} context with the production {@link JwtAuthenticationConverter}. */
public class WithMockJwtSecurityContextFactory implements WithSecurityContextFactory<WithMockJwt> {

    @Override
    public SecurityContext createSecurityContext(WithMockJwt annotation) {
        Jwt jwt = Jwt.withTokenValue(annotation.tokenValue())
                .header("alg", "RS256")
                .claim("sub", annotation.subject())
                .claim("realm_access", Map.of("roles", List.of(annotation.realmRoles())))
                .build();

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationConverter().convert(jwt));
        return context;
    }
}
