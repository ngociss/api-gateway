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

@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitFilter.class);

    @Autowired
    private MinuteRateLimiter rateLimiter;

    @Autowired
    private GatewayExceptionHandler exceptionHandler;

    private static final List<String> EXCLUDED_PATHS = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/eureka"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        if (isExcludedPath(path)) {
            logger.debug("Skipping rate limiting for public path: {}", path);
            return chain.filter(exchange);
        }

        String customerId = request.getHeaders().getFirst("X-Customer-Id");

        if (customerId == null) {
            customerId = "anonymous";
            logger.warn("No customerId found in header, using 'anonymous' for rate limiting");
        }
        String routeId = getRouteId(path);

        logger.debug("Checking rate limit for customer: {} on route: {}", customerId, routeId);

        String finalCustomerId = customerId;
        return rateLimiter.isAllowed(routeId, customerId)
                .flatMap(response -> {
                    response.getHeaders().forEach((key, value) ->
                        exchange.getResponse().getHeaders().add(key, value)
                    );

                    if (response.isAllowed()) {
                        logger.debug("Request allowed for customer: {}", finalCustomerId);
                        return chain.filter(exchange);
                    } else {
                        logger.warn("Rate limit exceeded for customer: {} on route: {}", finalCustomerId, routeId);
                        String message = String.format(
                            "Rate limit exceeded. Maximum %s requests per minute allowed.",
                            exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit")
                        );

                        return exceptionHandler.handleError(exchange, message, HttpStatus.TOO_MANY_REQUESTS);
                    }
                });
    }
    private boolean isExcludedPath(String path) {
        return EXCLUDED_PATHS.stream().anyMatch(path::startsWith);
    }

    private String getRouteId(String path) {
        if (path.startsWith("/api/orders")) {
            return "order-service";
        } else if (path.startsWith("/api/auth")) {
            return "auth-service";
        }
        return "default";
    }

    @Override
    public int getOrder() {
        return -90;
    }
}
