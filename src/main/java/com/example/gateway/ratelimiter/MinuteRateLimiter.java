package com.example.gateway.ratelimiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component
@Primary
public class MinuteRateLimiter implements RateLimiter<Object> {

    private static final Logger logger = LoggerFactory.getLogger(MinuteRateLimiter.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    @Value("${rate-limit.requests-per-minute:10}")
    private int requestLimitPerMinute;
    @Value("${rate-limit.window-seconds:60}")
    private int windowSeconds;

    @Autowired
    public MinuteRateLimiter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        String key = "rate_limit:customer:" + id + ":route:" + routeId;

        logger.debug("Checking rate limit for customer: {} on route: {}", id, routeId);
        return redisTemplate.opsForValue().increment(key)
                .flatMap(count -> {
                    if (count == 1) {
                        redisTemplate.expire(key, Duration.ofSeconds(windowSeconds))
                                .doOnSuccess(result -> logger.debug("Set TTL {} seconds for key: {}", windowSeconds, key))
                                .subscribe();
                    }
                    boolean allowed = count <= requestLimitPerMinute;
                    long remaining = Math.max(0, requestLimitPerMinute - count);

                    if (!allowed) {
                        logger.warn("Rate limit exceeded for customer: {} (request #{} > limit {})",
                                id, count, requestLimitPerMinute);
                    } else {
                        logger.debug("Rate limit check passed for customer: {} ({}/{})",
                                id, count, requestLimitPerMinute);
                    }

                    Map<String, String> headers = new HashMap<>();
                    headers.put("X-RateLimit-Limit", String.valueOf(requestLimitPerMinute));
                    headers.put("X-RateLimit-Remaining", String.valueOf(remaining));
                    headers.put("X-RateLimit-Reset", String.valueOf(windowSeconds));
                    return Mono.just(new Response(allowed, headers));
                })
                .onErrorResume(e -> {
                    logger.error("Error checking rate limit for customer: {}", id, e);

                    Map<String, String> headers = new HashMap<>();
                    headers.put("X-RateLimit-Error", "true");
                    return Mono.just(new Response(true, headers));
                });
    }

    @Override
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("requestsPerMinute", requestLimitPerMinute);
        config.put("windowSeconds", windowSeconds);
        return config;
    }

    @Override
    public Class<Object> getConfigClass() {
        return Object.class;
    }

    @Override
    public Object newConfig() {
        return new Object();
    }
}
