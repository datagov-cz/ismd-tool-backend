package com.dia.ismdtoolbackend.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.dia.constants.FormatConstants.Converter.LOG_REQUEST_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RequestIdMdcFilterTest {

    private final RequestIdMdcFilter filter = new RequestIdMdcFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesUuidWhenNoInboundHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> {
            mdcDuringChain.set(MDC.get(LOG_REQUEST_ID));
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(request, response, chain);

        String inChain = mdcDuringChain.get();
        assertThat(inChain).isNotBlank();
        UUID.fromString(inChain);
        assertThat(response.getHeader(RequestIdMdcFilter.REQUEST_ID_HEADER)).isEqualTo(inChain);
    }

    @Test
    void honorsValidInboundHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdMdcFilter.REQUEST_ID_HEADER, "trace-abc_123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> {
            mdcDuringChain.set(MDC.get(LOG_REQUEST_ID));
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(request, response, chain);

        assertThat(mdcDuringChain.get()).isEqualTo("trace-abc_123");
        assertThat(response.getHeader(RequestIdMdcFilter.REQUEST_ID_HEADER)).isEqualTo("trace-abc_123");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "has space",
            "with;semicolon",
            "newline\ninjection",
            "<script>",
            "carriage\rreturn",
            "tab\there",
            ""
    })
    void rejectsInvalidInboundHeaderAndGeneratesFresh(String inbound) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdMdcFilter.REQUEST_ID_HEADER, inbound);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> {
            mdcDuringChain.set(MDC.get(LOG_REQUEST_ID));
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(request, response, chain);

        String resolved = mdcDuringChain.get();
        assertThat(resolved).isNotEqualTo(inbound);
        UUID.fromString(resolved);
    }

    @Test
    void rejectsOversizeInboundHeader() throws Exception {
        String oversize = "a".repeat(129);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdMdcFilter.REQUEST_ID_HEADER, oversize);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> {
            mdcDuringChain.set(MDC.get(LOG_REQUEST_ID));
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(request, response, chain);

        String resolved = mdcDuringChain.get();
        assertThat(resolved).isNotEqualTo(oversize);
        UUID.fromString(resolved);
    }

    @Test
    void clearsMdcAfterChainCompletes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(MDC.get(LOG_REQUEST_ID)).isNull();
        verify(chain).doFilter(any(), any());
    }

    @Test
    void clearsMdcEvenWhenChainThrows() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> { throw new RuntimeException("boom"); }).when(chain).doFilter(any(), any());

        try {
            filter.doFilter(request, response, chain);
        } catch (RuntimeException expected) {
            // expected
        }

        assertThat(MDC.get(LOG_REQUEST_ID)).isNull();
    }
}
