package com.dia.ismdtoolbackend.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What each mode does with a request over the limit. */
class RateLimitFilterTest {

    private static RateLimitFilter filter(RateLimitConfig.Mode mode) {
        RateLimitConfig config = new RateLimitConfig();
        config.setMode(mode);
        RateLimitConfig.Group group = new RateLimitConfig.Group();
        group.setPaths(List.of("/api/eli/**"));
        group.setLimit(1);
        group.setPeriod(Duration.ofMinutes(1));
        config.getGroups().put("esbirka", group);
        return new RateLimitFilter(config, new RateLimiter(config), new ClientIpResolver(config));
    }

    private static MockHttpServletResponse call(RateLimitFilter filter) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/eli/resolve");
        request.setServletPath("/api/eli/resolve");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void enforce_rejectsTheCallOverTheLimit() throws Exception {
        RateLimitFilter filter = filter(RateLimitConfig.Mode.ENFORCE);

        assertThat(call(filter).getStatus()).isEqualTo(200);
        MockHttpServletResponse rejected = call(filter);
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getContentAsString()).contains("\"errorCode\":\"RATE_LIMITED\"");
    }

    @Test
    void log_letsTheCallOverTheLimitThrough() throws Exception {
        RateLimitFilter filter = filter(RateLimitConfig.Mode.LOG);

        call(filter);
        assertThat(call(filter).getStatus()).isEqualTo(200);
    }

    @Test
    void off_neverCounts() throws Exception {
        RateLimitFilter filter = filter(RateLimitConfig.Mode.OFF);

        for (int i = 0; i < 3; i++) {
            assertThat(call(filter).getStatus()).isEqualTo(200);
        }
    }
}
