package com.prioritize.security;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.prioritize.service.RefreshTokenService.IssuedRefreshToken;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class RefreshTokenCookieService {

    public static final String COOKIE_NAME = "prioritize_refresh";
    private static final String COOKIE_PATH = "/api/auth";
    private final boolean secure;

    public RefreshTokenCookieService(@Value("${app.refresh-token.cookie-secure:false}") boolean secure) {
        this.secure = secure;
    }

    public Optional<String> read(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    public void write(HttpServletResponse response, IssuedRefreshToken token) {
        long maxAge = Math.max(0, Duration.between(java.time.Instant.now(), token.expiresAt()).toSeconds());
        add(response, token.value(), Duration.ofSeconds(maxAge));
    }

    public void clear(HttpServletResponse response) {
        add(response, "", Duration.ZERO);
    }

    private void add(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
