package com.example.gateway.filter;

import com.example.gateway.exception.GatewayExceptionHandler;
import com.example.gateway.ratelimiter.MinuteRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Rate Limit Filter - Giới hạn số request theo từng user
 *
 * Chức năng:
 * - Kiểm tra số lượng request của user trong khoảng thời gian nhất định
 * - Sử dụng Redis để đếm số request (distributed, shared giữa các Gateway instances)
 * - Trả về 429 Too Many Requests nếu vượt quá giới hạn
 * - Thêm rate limit headers vào response để client biết
 *
 * Ví dụ:
 * - User A: Đã gửi 45/60 requests trong 1 phút → OK
 * - User B: Đã gửi 61/60 requests trong 1 phút → BLOCK (429)
 *
 * Order: -90 (chạy SAU JwtAuthenticationFilter nhưng TRƯỚC các filters khác)
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitFilter.class);

    @Autowired
    private MinuteRateLimiter rateLimiter;

    @Autowired
    private GatewayExceptionHandler exceptionHandler;

    /**
     * Danh sách các endpoint KHÔNG BỊ rate limiting
     * Login/Register không nên bị giới hạn quá chặt
     */
    private static final List<String> EXCLUDED_PATHS = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/eureka"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // BƯỚC 1: Kiểm tra path có cần rate limiting không?
        if (isExcludedPath(path)) {
            logger.debug("Skipping rate limiting for public path: {}", path);
            return chain.filter(exchange);
        }

        // BƯỚC 2: Lấy customerId từ header
        // Header này đã được JwtAuthenticationFilter thêm vào ở bước trước
        // Đó là lý do filter này PHẢI chạy SAU JWT filter
        String customerId = request.getHeaders().getFirst("X-Customer-Id");

        if (customerId == null) {
            // Trường hợp không có customerId (không nên xảy ra nếu JWT filter hoạt động đúng)
            customerId = "anonymous";
            logger.warn("No customerId found in header, using 'anonymous' for rate limiting");
        }

        // BƯỚC 3: Xác định route ID từ path
        // Mỗi route có counter riêng trong Redis
        // VD: User có thể gửi 60 req/min tới order-service VÀ 60 req/min tới auth-service
        String routeId = getRouteId(path);

        logger.debug("Checking rate limit for customer: {} on route: {}", customerId, routeId);

        // BƯỚC 4: Gọi MinuteRateLimiter để kiểm tra
        // rateLimiter.isAllowed() sẽ:
        // 1. Increment counter trong Redis
        // 2. Kiểm tra count <= limit
        // 3. Trả về Response với allowed=true/false + headers
        String finalCustomerId = customerId;
        return rateLimiter.isAllowed(routeId, customerId)
                .flatMap(response -> {
                    // BƯỚC 5: Thêm rate limit headers vào response
                    // Client có thể dùng để hiển thị "còn X requests"
                    response.getHeaders().forEach((key, value) ->
                        exchange.getResponse().getHeaders().add(key, value)
                    );

                    if (response.isAllowed()) {
                        // ✅ Chưa vượt limit, cho request đi tiếp
                        logger.debug("Request allowed for customer: {}", finalCustomerId);
                        return chain.filter(exchange);
                    } else {
                        // ❌ Đã vượt limit, trả về 429 Too Many Requests
                        logger.warn("Rate limit exceeded for customer: {} on route: {}", finalCustomerId, routeId);

                        // Tạo message có limit number để user biết
                        String message = String.format(
                            "Rate limit exceeded. Maximum %s requests per minute allowed.",
                            exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit")
                        );

                        return exceptionHandler.handleError(exchange, message, HttpStatus.TOO_MANY_REQUESTS);
                    }
                });
    }

    /**
     * Kiểm tra path có trong danh sách excluded không
     */
    private boolean isExcludedPath(String path) {
        return EXCLUDED_PATHS.stream().anyMatch(path::startsWith);
    }

    /**
     * Trích xuất route ID từ path
     *
     * Route ID dùng để phân biệt counter trong Redis:
     * - rate_limit:customer:123:route:order-service
     * - rate_limit:customer:123:route:auth-service
     *
     * Mỗi route có limit riêng, không ảnh hưởng lẫn nhau
     *
     * @param path Request path (vd: /api/orders/123)
     * @return Route ID (vd: "order-service")
     */
    private String getRouteId(String path) {
        if (path.startsWith("/api/orders")) {
            return "order-service";
        } else if (path.startsWith("/api/auth")) {
            return "auth-service";
        }
        return "default";
    }

    /**
     * Định nghĩa thứ tự chạy của filter
     *
     * @return -90 = Chạy SAU JWT filter (-100) nhưng TRƯỚC các filters khác
     *
     * Tại sao -90?
     * - Cần customerId từ JWT filter
     * - JWT filter có order -100 (chạy trước)
     * - Filter này chạy ngay sau để rate limit sớm nhất có thể
     */
    @Override
    public int getOrder() {
        return -90;
    }
}
