package com.example.gateway.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT Utility Class - Công cụ xử lý JWT Token
 *
 * Chức năng:
 * - Parse (phân tích) JWT token
 * - Validate (xác thực) token signature và expiration
 * - Extract (trích xuất) thông tin từ token (customerId, username, expiration)
 *
 * Lưu ý:
 * - Secret key PHẢI GIỐNG với auth-service
 * - Nếu secret khác nhau, token sẽ bị validate fail
 */
@Component
public class JwtUtil {

    /**
     * Secret key để verify JWT signature
     * QUAN TRỌNG: Phải GIỐNG secret key ở auth-service
     */
    @Value("${jwt.secret}")
    private String secret;

    /**
     * Thời gian token hết hạn (milliseconds)
     * Default: 15 phút (900000ms)
     */
    @Value("${jwt.access-token-expiration-ms:900000}")
    private Long expiration;

    /**
     * Tạo SecretKey từ string secret
     * Dùng HMAC-SHA algorithm (HS256, HS512)
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Parse token và lấy tất cả claims (thông tin) bên trong
     *
     * Claims là các thông tin được lưu trong JWT:
     * - sub: subject (username/email)
     * - customerId: ID của customer
     * - iat: issued at (thời gian tạo token)
     * - exp: expiration (thời gian hết hạn)
     *
     * @param token JWT token string
     * @return Claims object chứa tất cả thông tin
     * @throws Exception nếu token invalid (sai signature, hết hạn, etc.)
     */
    public Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())  // Set secret key để verify
                .build()
                .parseClaimsJws(token)           // Parse và verify token
                .getBody();                      // Lấy claims
    }

    /**
     * Lấy customerId từ token
     *
     * Thứ tự ưu tiên:
     * 1. Lấy từ claim "customerId" (nếu có)
     * 2. Fallback về "sub" (subject) nếu không có customerId
     *
     * @param token JWT token string
     * @return customerId của user
     */
    public String extractCustomerId(String token) {
        Claims claims = extractAllClaims(token);

        // Thử lấy từ claim "customerId"
        Object customerId = claims.get("customerId");
        if (customerId != null) {
            return customerId.toString();
        }

        // Nếu không có, fallback về subject
        return claims.getSubject();
    }

    /**
     * Lấy username từ token
     * Username thường được lưu trong claim "sub" (subject)
     *
     * @param token JWT token string
     * @return username hoặc email của user
     */
    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    /**
     * Lấy thời gian hết hạn của token
     *
     * @param token JWT token string
     * @return Date khi token hết hạn
     */
    public Date extractExpiration(String token) {
        return extractAllClaims(token).getExpiration();
    }

    /**
     * Kiểm tra token đã hết hạn chưa
     *
     * @param token JWT token string
     * @return true nếu token đã hết hạn
     */
    public Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    /**
     * Validate token
     *
     * Kiểm tra:
     * 1. Signature có đúng không (secret key match)
     * 2. Token có hết hạn chưa
     *
     * @param token JWT token string
     * @return true nếu token hợp lệ, false nếu không
     */
    public Boolean validateToken(String token) {
        try {
            return !isTokenExpired(token);
        } catch (Exception e) {
            // Nếu có bất kỳ exception nào (signature sai, format sai, etc.)
            // → Token không hợp lệ
            return false;
        }
    }
}
