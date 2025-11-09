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

@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private GatewayExceptionHandler exceptionHandler;

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

        if (isExcludedPath(path)) {
            logger.info("Skipping JWT validation for public path: {}", path);
            return chain.filter(exchange);
        }
        String authHeader = request.getHeaders().getFirst("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            logger.warn("Missing or invalid Authorization header for path: {}", path);
            return exceptionHandler.handleUnauthorized(exchange, "Missing or invalid Authorization header");
        }
        String token = authHeader.substring(7);

        try {
            if (!jwtUtil.validateToken(token)) {
                logger.warn("Invalid or expired token for path: {}", path);
                return exceptionHandler.handleUnauthorized(exchange, "Invalid or expired token");
            }
            String customerId = jwtUtil.extractCustomerId(token);
            String username = jwtUtil.extractUsername(token);

            logger.info("Authenticated user: {} (customerId: {})", username, customerId);
            ServerHttpRequest modifiedRequest = exchange.getRequest()
                    .mutate()
                    .header("X-Customer-Id", customerId)    // Header cho customerId
                    .header("X-Username", username)          // Header cho username
                    .build();
            return chain.filter(exchange.mutate().request(modifiedRequest).build());

        } catch (Exception e) {
            logger.error("Error validating JWT token: {}", e.getMessage());
            return exceptionHandler.handleUnauthorized(exchange, "Token validation failed: " + e.getMessage());
        }
    }

    private boolean isExcludedPath(String path) {
        return EXCLUDED_PATHS.stream().anyMatch(path::startsWith);
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
