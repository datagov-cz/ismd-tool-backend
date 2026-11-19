package com.dia.ismdtoolbackend.client.esbirka;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class EsbirkaDeadlineFilterTest {

    private final EsbirkaDeadlineFilter filter = new EsbirkaDeadlineFilter(60_000);

    @Test
    void eliRequestsRunUnderOneDeadlineThatIsClearedAfterwards() throws Exception {
        AtomicReference<Optional<EsbirkaDeadline>> during = new AtomicReference<>();

        filter.doFilter(request("/api/eli/law/content"), new MockHttpServletResponse(),
                (req, res) -> during.set(EsbirkaRequestDeadline.current()));

        assertThat(during.get()).isPresent();
        assertThat(during.get().get().remainingNanos()).isGreaterThan(0);
        assertThat(EsbirkaRequestDeadline.current())
                .as("cleared, so pooled threads do not inherit it")
                .isEqualTo(Optional.empty());
    }

    @Test
    void otherRequestsGetNoDeadline() throws Exception {
        AtomicReference<Optional<EsbirkaDeadline>> during = new AtomicReference<>();

        filter.doFilter(request("/api/ontologies"), new MockHttpServletResponse(),
                (req, res) -> during.set(EsbirkaRequestDeadline.current()));

        assertThat(during.get()).isEqualTo(Optional.empty());
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
        assertThat(EsbirkaRequestDeadline.current()).isEqualTo(Optional.empty());
    }

    private static MockHttpServletRequest request(String servletPath) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/popisujeme" + servletPath);
        request.setContextPath("/popisujeme");
        request.setServletPath(servletPath);
        return request;
    }
}
