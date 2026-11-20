package com.dia.ismdtoolbackend.ratelimit;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.enums.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Optional;

/**
 * Applies {@link RateLimiter} in the security chains that serve anonymous callers. The caller is the signed-in
 * user's id where the chain authenticated one, otherwise the client address from {@link ClientIpResolver}.
 * Not a bean, so it runs only in the chains that add it, never as a servlet filter of its own.
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RateLimitConfig config;
    private final RateLimiter limiter;
    private final ClientIpResolver ipResolver;

    public RateLimitFilter(RateLimitConfig config, RateLimiter limiter, ClientIpResolver ipResolver) {
        this.config = config;
        this.limiter = limiter;
        this.ipResolver = ipResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return config.getMode() == RateLimitConfig.Mode.OFF;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String caller = callerKey(request);
        Optional<RateLimiter.Decision> decision = limiter.consume(request, caller);
        if (log.isDebugEnabled()) {
            log.debug("Rate limit caller={} group={} xff='{}' peer={}", caller,
                    decision.map(RateLimiter.Decision::group).orElse("-"),
                    request.getHeader(ClientIpResolver.HEADER), request.getRemoteAddr());
        }
        if (decision.isEmpty() || decision.get().allowed()) {
            chain.doFilter(request, response);
            return;
        }
        RateLimiter.Decision rejected = decision.get();
        if (config.getMode() == RateLimitConfig.Mode.LOG) {
            log.warn("Rate limit would reject: group={} caller={} path={}", rejected.group(), caller,
                    request.getRequestURI());
            chain.doFilter(request, response);
            return;
        }
        log.info("Rate limit rejected: group={} caller={}", rejected.group(), caller);
        response.setStatus(ErrorCode.RATE_LIMITED.getStatus().value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(rejected.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(OBJECT_MAPPER.writeValueAsString(ApiResponseDto.error(ErrorCode.RATE_LIMITED)));
    }

    private String callerKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SecurityUser user) {
            return "user:" + user.getUserId();
        }
        return "ip:" + ipResolver.resolve(request);
    }
}
