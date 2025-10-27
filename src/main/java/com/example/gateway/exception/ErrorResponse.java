package com.example.gateway.exception;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Error Response Model
 *
 * Định nghĩa cấu trúc JSON response khi có lỗi
 *
 * Ví dụ response:
 * {
 *   "timestamp": "2025-10-25T00:45:30.123",
 *   "status": 401,
 *   "error": "Unauthorized",
 *   "message": "Invalid or expired token",
 *   "path": "/api/orders/123"
 * }
 *
 * Giúp client:
 * - Biết thời gian xảy ra lỗi (timestamp)
 * - Hiểu loại lỗi (status code và error name)
 * - Có message chi tiết để hiển thị cho user
 * - Biết endpoint nào gây lỗi (path)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {
    /** Thời gian xảy ra lỗi (ISO 8601 format) */
    private String timestamp;

    /** HTTP status code (401, 429, 500, etc.) */
    private int status;

    /** Tên lỗi (Unauthorized, Too Many Requests, etc.) */
    private String error;

    /** Mô tả chi tiết lỗi */
    private String message;

    /** Đường dẫn endpoint gây lỗi */
    private String path;

    /**
     * Constructor tiện lợi - tự động set timestamp hiện tại
     */
    public ErrorResponse(int status, String error, String message, String path) {
        this.timestamp = LocalDateTime.now().toString();
        this.status = status;
        this.error = error;
        this.message = message;
        this.path = path;
    }
}
