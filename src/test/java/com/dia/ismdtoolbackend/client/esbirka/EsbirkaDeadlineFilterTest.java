package com.dia.ismdtoolbackend.client.esbirka;

import com.dia.ismdtoolbackend.config.EsbirkaConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsbirkaDeadlineFilterTest {

    private final EsbirkaDeadlineFilter filter = new EsbirkaDeadlineFilter(
            new EsbirkaRestTransport(new EsbirkaConfig(), JsonMapper.builder().build(), new SimpleMeterRegistry()));

    @Test
    void eliRequestsRunUnderOneDeadlineThatIsClearedAfterwards() throws Exception {
        AtomicReference<Optional<EsbirkaDeadline>> during = new AtomicReference<>();

        filter.doFilter(request("/api/eli/law/content"), new MockHttpServletResponse(),
                (req, res) -> during.set(EsbirkaRequestDeadline.current()));

        assertTrue(during.get().isPresent());
        assertTrue(during.get().get().remainingNanos() > 0);
        assertEquals(Optional.empty(), EsbirkaRequestDeadline.current(), "cleared, so pooled threads do not inherit it");
    }

    @Test
    void otherRequestsGetNoDeadline() throws Exception {
        AtomicReference<Optional<EsbirkaDeadline>> during = new AtomicReference<>();

        filter.doFilter(request("/api/ontologies"), new MockHttpServletResponse(),
                (req, res) -> during.set(EsbirkaRequestDeadline.current()));

        assertEquals(Optional.empty(), during.get());
    }

    @Test
    void deadlineIsClearedWhenTheRequestFails() {
        try {
            filter.doFilter(request("/api/eli/resolve"), new MockHttpServletResponse(), (req, res) -> {
                throw new IllegalStateException("boom");
            });
        } catch (Exception expected) {
            // the failure itself is not under test
        }
        assertEquals(Optional.empty(), EsbirkaRequestDeadline.current());
    }

    private static MockHttpServletRequest request(String servletPath) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/popisujeme" + servletPath);
        request.setContextPath("/popisujeme");
        request.setServletPath(servletPath);
        return request;
    }
}
