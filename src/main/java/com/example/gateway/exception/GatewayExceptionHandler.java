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

/**
 * Gateway Exception Handler - Xử lý tập trung mọi lỗi
 *
 * Chức năng:
 * - Tạo ErrorResponse object với thông tin lỗi
 * - Convert object → JSON
 * - Write JSON vào HTTP response
 * - Set đúng status code và content-type
 *
 * Ưu điểm:
 * - Code tái sử dụng (không lặp lại logic tạo JSON)
 * - Format lỗi nhất quán (mọi lỗi đều có cùng structure)
 * - Dễ maintain (chỉ sửa ở 1 chỗ)
 */
@Component
public class GatewayExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    /** ObjectMapper để convert object → JSON */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Xử lý lỗi chung (generic error handler)
     *
     * @param exchange ServerWebExchange chứa request/response
     * @param message Thông báo lỗi
     * @param status HTTP status code
     * @return Mono<Void> - reactive response
     */
    public Mono<Void> handleError(ServerWebExchange exchange, String message, HttpStatus status) {
        ServerHttpResponse response = exchange.getResponse();

        // Set status code (401, 429, etc.)
        response.setStatusCode(status);

        // Set Content-Type = application/json
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        logger.error("Gateway error: {} - {}", status.value(), message);

        // Tạo ErrorResponse object
        ErrorResponse errorResponse = new ErrorResponse(
                status.value(),                              // status: 401
                status.getReasonPhrase(),                    // error: "Unauthorized"
                message,                                     // message: "Invalid token"
                exchange.getRequest().getURI().getPath()     // path: "/api/orders/123"
        );

        // Convert object → JSON → HTTP response
        return writeResponse(response, errorResponse);
    }

    /**
     * Xử lý lỗi 401 Unauthorized
     *
     * Dùng khi:
     * - Không có JWT token
     * - Token không hợp lệ
     * - Token hết hạn
     *
     * @param exchange ServerWebExchange
     * @param message Thông báo lỗi cụ thể
     * @return Mono<Void>
     */
    public Mono<Void> handleUnauthorized(ServerWebExchange exchange, String message) {
        return handleError(exchange, message, HttpStatus.UNAUTHORIZED);
    }

    /**
     * Xử lý lỗi 403 Forbidden
     *
     * Dùng khi:
     * - User đã authenticated nhưng không có quyền
     * - Truy cập resource của user khác
     *
     * @param exchange ServerWebExchange
     * @param message Thông báo lỗi cụ thể
     * @return Mono<Void>
     */
    public Mono<Void> handleForbidden(ServerWebExchange exchange, String message) {
        return handleError(exchange, message, HttpStatus.FORBIDDEN);
    }

    /**
     * Xử lý lỗi 400 Bad Request
     *
     * Dùng khi:
     * - Request data không hợp lệ
     * - Missing required parameters
     *
     * @param exchange ServerWebExchange
     * @param message Thông báo lỗi cụ thể
     * @return Mono<Void>
     */
    public Mono<Void> handleBadRequest(ServerWebExchange exchange, String message) {
        return handleError(exchange, message, HttpStatus.BAD_REQUEST);
    }

    /**
     * Write ErrorResponse object vào HTTP response
     *
     * Flow:
     * 1. Convert ErrorResponse → JSON string
     * 2. Convert JSON string → bytes
     * 3. Write bytes vào response body
     *
     * @param response ServerHttpResponse
     * @param errorResponse ErrorResponse object
     * @return Mono<Void>
     */
    private Mono<Void> writeResponse(ServerHttpResponse response, ErrorResponse errorResponse) {
        try {
            // Convert object → JSON bytes
            byte[] bytes = objectMapper.writeValueAsBytes(errorResponse);

            // Wrap bytes vào DataBuffer (reactive buffer)
            DataBuffer buffer = response.bufferFactory().wrap(bytes);

            // Write buffer vào response và return
            return response.writeWith(Mono.just(buffer));

        } catch (JsonProcessingException e) {
            // Nếu không convert được JSON (rất hiếm xảy ra)
            logger.error("Error creating error response", e);

            // Trả về empty response
            return response.setComplete();
        }
    }
}
