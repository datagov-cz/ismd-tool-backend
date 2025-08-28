package com.dia.ismdtoolbackend.config;

import com.dia.exceptions.DomainException;
import org.springframework.core.env.Environment;

import java.util.Arrays;

public final class DomainApplicationProfile {
    public static final String TEST = "test";
    public static final String NOT_TEST = "!" + TEST;

    public static final String LOCAL = "local";
    public static final String STAGE = "stage";
    public static final String NOT_STAGING = "!" + STAGE;

    public static final String PRODUCTION = "production";
    public static final String NOT_PRODUCTION = "!" + PRODUCTION;

    private DomainApplicationProfile() {
    }

    public static boolean isActive(Environment environment, String profileName) {
        return Arrays.asList(environment.getActiveProfiles()).contains(profileName);
    }

    public static String getProfile(Environment environment) {
        if (isActive(environment, PRODUCTION)) {
            return PRODUCTION;
        } else if (isActive(environment, STAGE)) {
            return STAGE;
        } else if (isActive(environment, LOCAL)) {
            return LOCAL;
        } else if (isActive(environment, TEST)) {
            return TEST;
        } else {
            throw new DomainException("Cannot determine current profile.");
        }
    }
}
