package com.dia.ismdtoolbackend.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The caller's address behind the proxies. {@code X-Forwarded-For} is read from the right, where each trusted
 * proxy appended the address it received the request from; the first entry that is not a trusted proxy is the
 * client. Entries to its left are whatever the client sent and are never used.
 */
@Component
public class ClientIpResolver {

    static final String HEADER = "X-Forwarded-For";

    private static final Pattern IPV4_WITH_PORT = Pattern.compile("^(\\d{1,3}(?:\\.\\d{1,3}){3}):\\d+$");
    private static final Pattern BRACKETED_IPV6 = Pattern.compile("^\\[([0-9A-Fa-f:.]+)](?::\\d+)?$");
    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private final List<IpAddressMatcher> trusted;

    public ClientIpResolver(RateLimitConfig config) {
        this.trusted = config.getTrustedProxies().stream().map(String::trim).filter(s -> !s.isEmpty())
                .map(IpAddressMatcher::new).toList();
    }

    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank() || !isTrusted(peer)) {
            return peer;
        }
        String[] entries = header.split(",");
        for (int i = entries.length - 1; i >= 0; i--) {
            String address = normalize(entries[i]);
            if (address == null) {
                return peer;
            }
            if (!isTrusted(address)) {
                return address;
            }
        }
        return peer;
    }

    /** The bare address of an entry, without port or brackets; null when it is not an IP literal. */
    static String normalize(String entry) {
        String value = entry.trim();
        var v4 = IPV4_WITH_PORT.matcher(value);
        if (v4.matches()) {
            return v4.group(1);
        }
        var v6 = BRACKETED_IPV6.matcher(value);
        if (v6.matches()) {
            return v6.group(1);
        }
        return IP_LITERAL.matcher(value).matches() && (value.contains(".") || value.contains(":")) ? value : null;
    }

    private boolean isTrusted(String address) {
        try {
            return trusted.stream().anyMatch(m -> m.matches(address));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
