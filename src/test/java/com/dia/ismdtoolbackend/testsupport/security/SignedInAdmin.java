package com.dia.ismdtoolbackend.testsupport.security;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

/**
 * Runs each test as a signed-in admin. Concept edit and delete refuse a caller without a user, and an admin
 * passes the ownership check without a lookup, so service-level tests need no ownership stubbing.
 */
public class SignedInAdmin implements BeforeEachCallback, AfterEachCallback {

    public static final String ADMIN_ID = "test-admin";

    @Override
    public void beforeEach(ExtensionContext context) {
        SecurityUser admin = new SecurityUser(ADMIN_ID, "Admin", List.of("ROLE_ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        SecurityContextHolder.clearContext();
    }
}
