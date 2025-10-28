package com.dia.ismdtoolbackend.config.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Custom UserDetails implementation for Keycloak OAuth2/OIDC authenticated users.
 * Extracts userId and roles from JWT claims (preferred_username/sub and roles).
 */
@Getter
public class SecurityUser implements UserDetails {

    private final String userId;
    private final List<String> roles;
    private final Collection<? extends GrantedAuthority> authorities;

    /**
     * Creates a SecurityUser from Keycloak JWT claims.
     *
     * @param userId User identifier from JWT "preferred_username" or "sub" claim
     * @param roles List of role strings extracted from JWT "realm_access.roles" claim
     */
    public SecurityUser(String userId, List<String> roles) {
        this.userId = userId;
        this.roles = roles;
        this.authorities = roles.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        // OAuth2 users don't have passwords
        return null;
    }

    @Override
    public String getUsername() {
        // Return userId as username for Spring Security
        return userId;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    /**
     * Checks if user has admin role.
     *
     * @return true if user has ROLE_ADMIN authority
     */
    public boolean isAdmin() {
        return authorities.stream()
                .anyMatch(auth -> "ROLE_ADMIN".equals(auth.getAuthority()));
    }
}
