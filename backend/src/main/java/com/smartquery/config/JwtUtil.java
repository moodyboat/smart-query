package com.smartquery.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 生成与解析工具（HS256）。
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${smart-query.jwt.secret}")
    private String secret;

    @Value("${smart-query.jwt.expiration-minutes:1440}")
    private long expirationMinutes;

    @Value("${smart-query.jwt.issuer:smart-query}")
    private String issuer;

    private SecretKey key;

    @PostConstruct
    void init() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("smart-query.jwt.secret 必须至少 32 字节（HS256 要求）");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generate(Long userId, String username, String role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
            .issuer(issuer)
            .subject(String.valueOf(userId))
            .claim("username", username)
            .claim("role", role)
            .issuedAt(new Date(now))
            .expiration(new Date(now + expirationMinutes * 60_000L))
            .signWith(key, Jwts.SIG.HS256)
            .compact();
    }

    public long getExpirationSeconds() {
        return expirationMinutes * 60L;
    }

    public Claims parse(String token) throws JwtException {
        return Jwts.parser()
            .verifyWith(key)
            .requireIssuer(issuer)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }
}
