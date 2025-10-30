package com.dia.ismdtoolbackend.config.security;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts JWT token from Keycloak to Spring Security Authentication with SecurityUser.
 * Extracts userId from "preferred_username" (with fallback to "sub") and roles from "realm_access.roles".
 */
@Slf4j
@Component
public class JwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String CLAIM_PREFERRED_USERNAME = "preferred_username";
    private static final String CLAIM_SUB = "sub";
    private static final String CLAIM_REALM_ACCESS = "realm_access";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        // Extract userId from "sub" claim (Keycloak UUID)
        String userId = StringUtils.trim(jwt.getClaimAsString(CLAIM_SUB));

        if (StringUtils.isEmpty(userId)) {
            log.error("JWT missing both 'preferred_username' and 'sub' claims");
            throw new IllegalArgumentException("JWT must contain 'preferred_username' or 'sub' claim with user ID");
        }

        // Extract username for display purposes
        String displayName = StringUtils.trim(jwt.getClaimAsString(CLAIM_PREFERRED_USERNAME));

        // Extract roles from Keycloak nested structure: realm_access.roles
        List<String> roles = extractKeycloakRoles(jwt);

        log.debug("Converting JWT to SecurityUser: userId={}, roles={}", userId, roles);

        // Create SecurityUser with extracted claims
        SecurityUser securityUser = new SecurityUser(userId, displayName, roles);

        // Return custom Authentication with SecurityUser as principal
        return new SecurityUserAuthentication(securityUser, jwt);
    }

    /**
     * Custom AbstractAuthenticationToken that uses SecurityUser as principal.
     */
    private static class SecurityUserAuthentication extends AbstractAuthenticationToken {
        private final SecurityUser principal;
        private final Jwt jwt;

        public SecurityUserAuthentication(SecurityUser principal, Jwt jwt) {
            super(principal.getAuthorities());
            this.principal = principal;
            this.jwt = jwt;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return jwt;
        }

        @Override
        public Object getPrincipal() {
            return principal;
        }

        @Override
        public String getName() {
            return principal.getUserId();
        }
    }

    /**
     * Extracts roles from Keycloak JWT.
     * Keycloak stores roles in nested structure: {"realm_access": {"roles": ["admin", "user"]}}
     * Automatically adds "ROLE_" prefix if not present (Spring Security convention).
     *
     * @param jwt JWT token
     * @return List of role strings with ROLE_ prefix
     */
    private List<String> extractKeycloakRoles(Jwt jwt) {
        try {
            // Try realm_access.roles first (standard Keycloak realm roles)
            Map<String, Object> realmAccess = jwt.getClaim(CLAIM_REALM_ACCESS);
            if (realmAccess != null && realmAccess.containsKey("roles")) {
                Object rolesObj = realmAccess.get("roles");
                if (rolesObj instanceof List<?>) {
                    return ((List<?>) rolesObj).stream()
                            .map(Object::toString)
                            .map(this::ensureRolePrefix)
                            .filter(Objects::nonNull)
                            .toList();
                }
            }

            // Fallback: try flat "roles" claim (if custom mapper configured in Keycloak)
            Object rolesClaim = jwt.getClaim("roles");
            if (rolesClaim instanceof List<?>) {
                return ((List<?>) rolesClaim).stream()
                        .map(Object::toString)
                        .map(this::ensureRolePrefix)
                        .filter(Objects::nonNull)
                        .toList();
            }

            log.warn("JWT missing 'realm_access.roles' claim, defaulting to empty roles");
            return List.of();
        } catch (Exception e) {
            log.error("Failed to extract roles from JWT: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Ensures role has "ROLE_" prefix (Spring Security convention).
     * If role already has prefix, returns as-is.
     * Returns null for invalid roles (null or empty) which will be filtered out.
     *
     * @param role role name (e.g., "admin" or "ROLE_ADMIN")
     * @return role with ROLE_ prefix (e.g., "ROLE_ADMIN"), or null if invalid
     */
    private String ensureRolePrefix(String role) {
        if (StringUtils.isEmpty(role)) {
            log.warn("Encountered null or empty role, skipping");
            return null;
        }
        String upperRole = role.toUpperCase();
        return upperRole.startsWith("ROLE_") ? upperRole : "ROLE_" + upperRole;
    }
}
