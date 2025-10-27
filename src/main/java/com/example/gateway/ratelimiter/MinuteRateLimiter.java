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

/**
 * Custom Rate Limiter - Giới hạn số request theo phút
 *
 * Cách hoạt động:
 * 1. Mỗi request tăng counter trong Redis
 * 2. Counter tự động reset sau window time (TTL)
 * 3. Nếu counter > limit → từ chối request
 *
 * Redis Key Format:
 * rate_limit:customer:{customerId}:route:{routeId}
 *
 * Ví dụ:
 * - rate_limit:customer:12345:route:order-service = 45
 * - User 12345 đã gửi 45 requests tới order-service
 * - Còn 15 requests nữa (limit = 60)
 *
 * Ưu điểm Redis:
 * - Atomic: INCR operation thread-safe
 * - TTL: Tự động xóa key khi hết window
 * - Distributed: Nhiều Gateway instances share cùng counter
 */
@Component
@Primary
public class MinuteRateLimiter implements RateLimiter<Object> {

    private static final Logger logger = LoggerFactory.getLogger(MinuteRateLimiter.class);

    private final ReactiveStringRedisTemplate redisTemplate;

    /**
     * Số request tối đa mỗi phút
     * Có thể config qua: rate-limit.requests-per-minute
     */
    @Value("${rate-limit.requests-per-minute:10}")
    private int requestLimitPerMinute;

    /**
     * Thời gian window (giây)
     * Sau thời gian này, counter sẽ reset
     * Có thể config qua: rate-limit.window-seconds
     */
    @Value("${rate-limit.window-seconds:60}")
    private int windowSeconds;

    @Autowired
    public MinuteRateLimiter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Kiểm tra request có được phép hay không
     *
     * @param routeId ID của route (vd: "order-service")
     * @param id customerId từ JWT token
     * @return Response với allowed=true/false và headers
     */
    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        // BƯỚC 1: Tạo Redis key unique cho user + route
        // Mỗi user mỗi route có counter riêng
        String key = "rate_limit:customer:" + id + ":route:" + routeId;

        logger.debug("Checking rate limit for customer: {} on route: {}", id, routeId);

        // BƯỚC 2: Increment (tăng) counter trong Redis
        // INCR là atomic operation - thread-safe
        return redisTemplate.opsForValue().increment(key)
                .flatMap(count -> {
                    // BƯỚC 3: Nếu là request đầu tiên (count = 1)
                    // Set TTL để key tự động xóa sau window time
                    if (count == 1) {
                        redisTemplate.expire(key, Duration.ofSeconds(windowSeconds))
                                .doOnSuccess(result -> logger.debug("Set TTL {} seconds for key: {}", windowSeconds, key))
                                .subscribe();
                    }

                    // BƯỚC 4: Kiểm tra count có vượt limit không
                    boolean allowed = count <= requestLimitPerMinute;
                    long remaining = Math.max(0, requestLimitPerMinute - count);

                    if (!allowed) {
                        // Request bị từ chối - log warning
                        logger.warn("Rate limit exceeded for customer: {} (request #{} > limit {})",
                                id, count, requestLimitPerMinute);
                    } else {
                        // Request được chấp nhận - log debug
                        logger.debug("Rate limit check passed for customer: {} ({}/{})",
                                id, count, requestLimitPerMinute);
                    }

                    // BƯỚC 5: Tạo response headers
                    // Client có thể dùng để hiển thị UI
                    Map<String, String> headers = new HashMap<>();
                    headers.put("X-RateLimit-Limit", String.valueOf(requestLimitPerMinute));      // Limit tối đa
                    headers.put("X-RateLimit-Remaining", String.valueOf(remaining));              // Số request còn lại
                    headers.put("X-RateLimit-Reset", String.valueOf(windowSeconds));              // Thời gian reset

                    // BƯỚC 6: Trả về Response
                    return Mono.just(new Response(allowed, headers));
                })
                .onErrorResume(e -> {
                    // FAIL-OPEN POLICY
                    // Nếu Redis bị lỗi, CHO PHÉP request đi qua
                    // Điều này đảm bảo service vẫn hoạt động khi Redis down
                    logger.error("Error checking rate limit for customer: {}", id, e);

                    Map<String, String> headers = new HashMap<>();
                    headers.put("X-RateLimit-Error", "true");

                    // allowed = true → Cho request đi qua
                    return Mono.just(new Response(true, headers));
                });
    }

    /**
     * Trả về config của rate limiter
     * Dùng cho monitoring/debugging
     */
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
