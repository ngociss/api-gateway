package com.example.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@Configuration
public class GatewayConfig {

    @Bean
    public KeyResolver customerKeyResolver() {
        return exchange -> {
            String customerId = exchange.getRequest().getHeaders().getFirst("X-Customer-Id");
            if (customerId == null) customerId = "anonymous";
            return Mono.just(customerId);
        };
    }
}
