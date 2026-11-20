package com.dia.ismdtoolbackend.ratelimit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-caller request limits on the public and search endpoints. Operating instructions:
 * {@code docs/RATE_LIMITING.md}.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitConfig {

    public enum Mode {
        /** No limiting. */
        OFF,
        /** Counts and logs what would be rejected, rejects nothing. */
        LOG,
        /** Rejects over-limit requests with 429. */
        ENFORCE
    }

    private Mode mode = Mode.OFF;

    /**
     * Proxies whose {@code X-Forwarded-For} entries are skipped when finding the client, as CIDRs or single
     * addresses. The client is the right-most entry not listed here.
     */
    private List<String> trustedProxies = new ArrayList<>(List.of(
            "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "127.0.0.0/8", "::1/128", "fc00::/7"));

    /** Most callers tracked at once; the least recently seen are dropped first. */
    private int maxTrackedCallers = 100_000;

    /** Endpoint groups, each with its own budget per caller. A request matches at most one group. */
    private Map<String, Group> groups = new LinkedHashMap<>();

    @Data
    public static class Group {
        /** Path patterns within the application, e.g. {@code /api/eli/**}. */
        private List<String> paths = new ArrayList<>();
        /** Requests allowed per {@link #period}; also the burst size. */
        private int limit;
        private Duration period = Duration.ofMinutes(1);
    }
}
