package com.example.gateway.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;


@Component
public class GatewayExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GatewayExceptionHandler.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    public Mono<Void> handleError(ServerWebExchange exchange, String message, HttpStatus status) {
        ServerHttpResponse response = exchange.getResponse();

        response.setStatusCode(status);

        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        logger.error("Gateway error: {} - {}", status.value(), message);

        ErrorResponse errorResponse = new ErrorResponse(
                status.value(),
                status.getReasonPhrase(),
                message,
                exchange.getRequest().getURI().getPath()
        );

        return writeResponse(response, errorResponse);
    }

    public Mono<Void> handleUnauthorized(ServerWebExchange exchange, String message) {
        return handleError(exchange, message, HttpStatus.UNAUTHORIZED);
    }

    public Mono<Void> handleForbidden(ServerWebExchange exchange, String message) {
        return handleError(exchange, message, HttpStatus.FORBIDDEN);
    }

    public Mono<Void> handleBadRequest(ServerWebExchange exchange, String message) {
        return handleError(exchange, message, HttpStatus.BAD_REQUEST);
    }

    private Mono<Void> writeResponse(ServerHttpResponse response, ErrorResponse errorResponse) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(errorResponse);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            logger.error("Error creating error response", e);
            return response.setComplete();
        }
    }
}
