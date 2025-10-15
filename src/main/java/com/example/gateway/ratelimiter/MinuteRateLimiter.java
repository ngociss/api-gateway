package com.example.gateway.ratelimiter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;


@Component
@Primary
public class MinuteRateLimiter implements RateLimiter<Object> {

    private final ReactiveStringRedisTemplate redisTemplate;

    private static final int REQUEST_LIMIT_PER_MINUTE = 10;

    @Autowired
    public MinuteRateLimiter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        String key = "rate:" + routeId + ":" + id;

        return redisTemplate.opsForValue().increment(key)
                .flatMap(count -> {
                    // Nếu là lần đầu tiên, đặt TTL 60 giây cho key
                    if (count == 1) {
                        redisTemplate.expire(key, Duration.ofMinutes(1)).subscribe();
                    }

                    boolean allowed = count <= REQUEST_LIMIT_PER_MINUTE;

                    Map<String, String> headers = new HashMap<>();
                    headers.put("X-RateLimit-Limit", String.valueOf(REQUEST_LIMIT_PER_MINUTE));
                    headers.put("X-RateLimit-Remaining", String.valueOf(Math.max(0, REQUEST_LIMIT_PER_MINUTE - count)));

                    return Mono.just(new Response(allowed, headers));
                });
    }

    @Override
    public Map<String, Object> getConfig() {
        return Collections.emptyMap();
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
