package com.prioritize.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.prioritize.dto.AuthConfigResponse;
import com.prioritize.dto.AuthResponse;
import com.prioritize.dto.LoginRequest;
import com.prioritize.dto.RegisterRequest;
import com.prioritize.dto.UserResponse;
import com.prioritize.config.OAuthProperties;
import com.prioritize.security.UserPrincipal;
import com.prioritize.security.RefreshTokenCookieService;
import com.prioritize.service.AuthService;
import com.prioritize.service.RefreshTokenService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final OAuthProperties oauthProperties;
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenCookieService refreshTokenCookieService;

    public AuthController(AuthService authService, OAuthProperties oauthProperties,
            RefreshTokenService refreshTokenService, RefreshTokenCookieService refreshTokenCookieService) {
        this.authService = authService;
        this.oauthProperties = oauthProperties;
        this.refreshTokenService = refreshTokenService;
        this.refreshTokenCookieService = refreshTokenCookieService;
    }

    @GetMapping("/config")
    public ResponseEntity<AuthConfigResponse> config() {
        return ResponseEntity.ok(new AuthConfigResponse(oauthProperties.getGoogle().isEnabled()));
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
            HttpServletResponse response) {
        AuthResponse auth = authService.register(request);
        setRefreshCookie(response, auth);
        return ResponseEntity.status(HttpStatus.CREATED).body(auth);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {
        AuthResponse auth = authService.login(request);
        setRefreshCookie(response, auth);
        return ResponseEntity.ok(auth);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
        String rawToken = refreshTokenCookieService.read(request)
                .orElseThrow(() -> new com.prioritize.exception.ApiException(
                        HttpStatus.UNAUTHORIZED, "Refresh session is missing"));
        RefreshTokenService.RefreshResult result = refreshTokenService.rotate(rawToken);
        refreshTokenCookieService.write(response, result.refreshToken());
        return ResponseEntity.ok(result.auth());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        refreshTokenCookieService.read(request).ifPresent(refreshTokenService::revoke);
        refreshTokenCookieService.clear(response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(authService.me(principal));
    }

    private void setRefreshCookie(HttpServletResponse response, AuthResponse auth) {
        refreshTokenCookieService.write(response, refreshTokenService.issue(auth.user().id()));
    }
}
