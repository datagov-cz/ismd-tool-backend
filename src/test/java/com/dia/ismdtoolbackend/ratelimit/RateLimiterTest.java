package com.dia.ismdtoolbackend.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Buckets per caller and group. */
class RateLimiterTest {

    private static RateLimitConfig config(int limit, Duration period) {
        RateLimitConfig config = new RateLimitConfig();
        RateLimitConfig.Group esbirka = new RateLimitConfig.Group();
        esbirka.setPaths(List.of("/api/eli/**"));
        esbirka.setLimit(limit);
        esbirka.setPeriod(period);
        config.getGroups().put("esbirka", esbirka);
        RateLimitConfig.Group search = new RateLimitConfig.Group();
        search.setPaths(List.of("/api/search/**", "/api/rpp/*/search"));
        search.setLimit(limit);
        search.setPeriod(period);
        config.getGroups().put("search", search);
        return config;
    }

    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }

    @Test
    void theRequestOverTheLimitIsRejectedWithARetryAfter() {
        RateLimiter limiter = new RateLimiter(config(2, Duration.ofMinutes(1)));

        assertThat(limiter.consume(get("/api/eli/law/versions"), "ip:a")).hasValueSatisfying(d -> assertThat(d.allowed()).isTrue());
        assertThat(limiter.consume(get("/api/eli/law/content"), "ip:a")).hasValueSatisfying(d -> assertThat(d.allowed()).isTrue());
        assertThat(limiter.consume(get("/api/eli/resolve"), "ip:a")).hasValueSatisfying(d -> {
            assertThat(d.allowed()).isFalse();
            assertThat(d.group()).isEqualTo("esbirka");
            assertThat(d.retryAfterSeconds()).isBetween(1L, 60L);
        });
    }

    @Test
    void callersAndGroupsHaveSeparateBudgets() {
        RateLimiter limiter = new RateLimiter(config(1, Duration.ofMinutes(1)));

        assertThat(limiter.consume(get("/api/eli/resolve"), "ip:a").orElseThrow().allowed()).isTrue();
        assertThat(limiter.consume(get("/api/eli/resolve"), "ip:b").orElseThrow().allowed()).isTrue();
        assertThat(limiter.consume(get("/api/rpp/agenda/search"), "ip:a").orElseThrow().allowed()).isTrue();
        assertThat(limiter.consume(get("/api/eli/resolve"), "ip:a").orElseThrow().allowed()).isFalse();
    }

    @Test
    void theBudgetRefillsOverThePeriod() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(config(1, Duration.ofMillis(200)));
        limiter.consume(get("/api/search"), "ip:a");
        assertThat(limiter.consume(get("/api/search"), "ip:a").orElseThrow().allowed()).isFalse();

        Thread.sleep(300);

        assertThat(limiter.consume(get("/api/search"), "ip:a").orElseThrow().allowed()).isTrue();
    }

    @Test
    void aPathInNoGroupIsNotCounted() {
        RateLimiter limiter = new RateLimiter(config(1, Duration.ofMinutes(1)));

        assertThat(limiter.consume(get("/api/ontology/list"), "ip:a")).isEmpty();
    }

    @Test
    void aGroupWithoutALimitFailsStartup() {
        RateLimitConfig config = config(1, Duration.ofMinutes(1));
        config.getGroups().get("search").setLimit(0);

        assertThatThrownBy(() -> new RateLimiter(config)).hasMessageContaining("rate-limit.groups.search");
    }
}
