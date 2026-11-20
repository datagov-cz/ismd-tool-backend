package com.dia.ismdtoolbackend.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One token bucket per caller and endpoint group, held in memory. Exact while the backend runs as a single
 * instance; with more replicas each one would count separately.
 */
@Component
public class RateLimiter {

    /** A decision: allowed, or rejected with the seconds until a token is available. */
    public record Decision(String group, boolean allowed, long retryAfterSeconds) {
    }

    private record Rule(String name, RequestMatcher matcher, RateLimitConfig.Group limits) {
    }

    private final List<Rule> rules;
    private final Cache<String, Bucket> buckets;

    public RateLimiter(RateLimitConfig config) {
        this.rules = config.getGroups().entrySet().stream().map(RateLimiter::rule).toList();
        Duration longest = rules.stream().map(r -> r.limits().getPeriod()).max(Duration::compareTo)
                .orElse(Duration.ofMinutes(1));
        // A bucket idle for a full period is full again, so dropping it loses nothing.
        this.buckets = Caffeine.newBuilder()
                .maximumSize(config.getMaxTrackedCallers())
                .expireAfterAccess(longest)
                .build();
    }

    /** Empty when the request is in no group. */
    public Optional<Decision> consume(HttpServletRequest request, String callerKey) {
        for (Rule rule : rules) {
            if (rule.matcher().matches(request)) {
                Bucket bucket = buckets.get(rule.name() + '|' + callerKey, k -> newBucket(rule.limits()));
                ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
                long retryAfter = probe.isConsumed() ? 0
                        : Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds() + 1);
                return Optional.of(new Decision(rule.name(), probe.isConsumed(), retryAfter));
            }
        }
        return Optional.empty();
    }

    private static Bucket newBucket(RateLimitConfig.Group limits) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(limits.getLimit())
                        .refillGreedy(limits.getLimit(), limits.getPeriod())
                        .build())
                .build();
    }

    private static Rule rule(Map.Entry<String, RateLimitConfig.Group> entry) {
        RateLimitConfig.Group limits = entry.getValue();
        if (limits.getLimit() < 1 || limits.getPaths().isEmpty()) {
            throw new IllegalStateException(
                    "rate-limit.groups." + entry.getKey() + " needs a limit of at least 1 and at least one path");
        }
        List<RequestMatcher> matchers = limits.getPaths().stream()
                .map(String::trim)
                .<RequestMatcher>map(p -> PathPatternRequestMatcher.withDefaults().matcher(p))
                .toList();
        return new Rule(entry.getKey(), new OrRequestMatcher(matchers), limits);
    }
}
