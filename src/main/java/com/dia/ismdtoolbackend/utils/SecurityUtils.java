package com.dia.ismdtoolbackend.utils;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SecurityUtils {

    /**
     * Extracts SecurityUser from current SecurityContext.
     *
     * @return SecurityUser object from JWT authentication
     * @throws IllegalStateException if user is not authenticated or principal is not SecurityUser
     */
    public static SecurityUser getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null) {
            log.error("No authentication found in SecurityContext");
            throw new IllegalStateException("User not authenticated");
        }

        Object principal = auth.getPrincipal();
        if (!(principal instanceof SecurityUser)) {
            log.error("Principal is not SecurityUser: {}", principal.getClass().getName());
            throw new IllegalStateException("Invalid authentication principal type");
        }

        return (SecurityUser) principal;
    }
}
