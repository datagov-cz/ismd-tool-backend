package com.dia.ismdtoolbackend.client.esbirka;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Gives each {@code /api/eli/*} request one e-Sbírka time budget, shared by every REST call it
 * makes — so a content load is bounded as a whole, not per call.
 */
@Component
@ConditionalOnProperty(name = "esbirka.provider", havingValue = "rest")
class EsbirkaDeadlineFilter extends OncePerRequestFilter {

    private static final String ELI_PATH = "/api/eli/";

    private final EsbirkaRestTransport transport;

    EsbirkaDeadlineFilter(EsbirkaRestTransport transport) {
        this.transport = transport;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !request.getServletPath().startsWith(ELI_PATH);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {
        EsbirkaRequestDeadline.begin(transport.newDeadline());
        try {
            chain.doFilter(request, response);
        } finally {
            EsbirkaRequestDeadline.end();
        }
    }
}
