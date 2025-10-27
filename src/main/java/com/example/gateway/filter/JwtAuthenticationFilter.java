package com.example.gateway.filter;

import com.example.gateway.exception.GatewayExceptionHandler;
import com.example.gateway.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * JWT Authentication Filter
 *
 * Chức năng:
 * - Xác thực JWT token từ Authorization header
 * - Trích xuất thông tin user (customerId, username) từ token
 * - Thêm thông tin vào request headers để các service khác sử dụng
 *
 * Flow:
 * 1. Kiểm tra path có cần xác thực không (public endpoints bỏ qua)
 * 2. Lấy token từ Authorization header
 * 3. Validate token với JwtUtil
 * 4. Extract customerId và username
 * 5. Thêm X-Customer-Id và X-Username vào request headers
 * 6. Cho request đi tiếp
 *
 * Order: -100 (chạy ĐẦU TIÊN trước tất cả filters khác)
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private GatewayExceptionHandler exceptionHandler;

    /**
     * Danh sách các endpoint KHÔNG CẦN xác thực JWT
     * Ví dụ: login, register, refresh token
     */
    private static final List<String> EXCLUDED_PATHS = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/eureka"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        logger.info("Processing request: {} {}", request.getMethod(), path);

        // BƯỚC 1: Kiểm tra path có trong danh sách public endpoints không?
        if (isExcludedPath(path)) {
            logger.info("Skipping JWT validation for public path: {}", path);
            return chain.filter(exchange); // Bỏ qua xác thực, cho đi tiếp
        }

        // BƯỚC 2: Lấy Authorization header
        // Format mong đợi: "Authorization: Bearer <token>"
        String authHeader = request.getHeaders().getFirst("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            logger.warn("Missing or invalid Authorization header for path: {}", path);
            // Trả về 401 Unauthorized với message rõ ràng
            return exceptionHandler.handleUnauthorized(exchange, "Missing or invalid Authorization header");
        }

        // BƯỚC 3: Trích xuất token (bỏ "Bearer " prefix)
        String token = authHeader.substring(7);

        try {
            // BƯỚC 4: Validate token
            // JwtUtil sẽ kiểm tra:
            // - Signature có hợp lệ không (đúng secret key)
            // - Token có hết hạn chưa (expiration time)
            if (!jwtUtil.validateToken(token)) {
                logger.warn("Invalid or expired token for path: {}", path);
                return exceptionHandler.handleUnauthorized(exchange, "Invalid or expired token");
            }

            // BƯỚC 5: Lấy thông tin từ token
            // customerId: ID của user, dùng cho rate limiting và authorization
            // username: Email hoặc username, dùng cho logging/audit
            String customerId = jwtUtil.extractCustomerId(token);
            String username = jwtUtil.extractUsername(token);

            logger.info("Authenticated user: {} (customerId: {})", username, customerId);

            // BƯỚC 6: THÊM thông tin vào request headers
            // Các service downstream (order-service, etc.) sẽ nhận được headers này
            // và biết request đến từ user nào
            ServerHttpRequest modifiedRequest = exchange.getRequest()
                    .mutate()
                    .header("X-Customer-Id", customerId)    // Header cho customerId
                    .header("X-Username", username)          // Header cho username
                    .build();

            // BƯỚC 7: Cho request đi tiếp với headers đã được thêm vào
            return chain.filter(exchange.mutate().request(modifiedRequest).build());

        } catch (Exception e) {
            // Bắt mọi exception khi validate token (JWT parse error, etc.)
            logger.error("Error validating JWT token: {}", e.getMessage());
            return exceptionHandler.handleUnauthorized(exchange, "Token validation failed: " + e.getMessage());
        }
    }

    /**
     * Kiểm tra path có trong danh sách excluded paths không
     *
     * @param path Request path (vd: /api/auth/login)
     * @return true nếu path không cần xác thực
     */
    private boolean isExcludedPath(String path) {
        return EXCLUDED_PATHS.stream().anyMatch(path::startsWith);
    }

    /**
     * Định nghĩa thứ tự chạy của filter
     *
     * @return -100 = Chạy ĐẦU TIÊN (số càng nhỏ càng ưu tiên)
     *
     * Tại sao -100?
     * - RateLimitFilter cần customerId từ header
     * - customerId được filter này thêm vào
     * - Nên JWT filter PHẢI chạy trước RateLimitFilter (-90)
     */
    @Override
    public int getOrder() {
        return -100;
    }
}
