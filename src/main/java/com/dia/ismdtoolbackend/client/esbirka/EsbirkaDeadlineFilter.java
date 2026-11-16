package com.dia.ismdtoolbackend.client.esbirka;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Gives each {@code /api/eli/*} request one e-Sbírka time budget, shared by every REST call it
 * makes — so a content load is bounded as a whole, not per call.
 * <p>
 * Reads the budget from the property, not from {@link EsbirkaRestTransport}: {@code @WebMvcTest} slices
 * load every servlet filter but not the transport.
 */
@Component
@ConditionalOnProperty(name = "esbirka.provider", havingValue = "rest")
class EsbirkaDeadlineFilter extends OncePerRequestFilter {

    private static final String ELI_PATH = "/api/eli/";

    private final Duration requestDeadline;

    EsbirkaDeadlineFilter(@Value("${esbirka.rest.request-deadline-ms}") long requestDeadlineMs) {
        this.requestDeadline = Duration.ofMillis(requestDeadlineMs);
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !request.getServletPath().startsWith(ELI_PATH);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {
        EsbirkaRequestDeadline.begin(EsbirkaDeadline.after(requestDeadline));
        try {
            chain.doFilter(request, response);
        } finally {
            EsbirkaRequestDeadline.end();
        }
    }
}
