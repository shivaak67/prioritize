package com.prioritize.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.prioritize.dto.AuthResponse;
import com.prioritize.exception.ApiException;
import com.prioritize.mapper.UserMapper;
import com.prioritize.model.RefreshToken;
import com.prioritize.model.User;
import com.prioritize.repository.RefreshTokenRepository;
import com.prioritize.repository.UserRepository;
import com.prioritize.security.JwtService;

@Service
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final RefreshTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final Duration lifetime;

    public RefreshTokenService(
            RefreshTokenRepository tokenRepository,
            UserRepository userRepository,
            JwtService jwtService,
            @Value("${app.refresh-token.expiration-days:30}") long expirationDays) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.lifetime = Duration.ofDays(expirationDays);
    }

    @Transactional
    public IssuedRefreshToken issue(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "User not found"));
        return create(user);
    }

    @Transactional
    public RefreshResult rotate(String rawToken) {
        Instant now = Instant.now();
        RefreshToken current = tokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(this::invalidToken);
        if (current.getRevokedAt() != null || !current.getExpiresAt().isAfter(now)) {
            throw invalidToken();
        }

        current.setRevokedAt(now);
        IssuedRefreshToken replacement = create(current.getUser());
        current.setReplacedByTokenId(replacement.id());
        tokenRepository.save(current);

        User user = current.getUser();
        String accessToken = jwtService.generateToken(user.getId(), user.getEmail());
        return new RefreshResult(
                new AuthResponse(accessToken, jwtService.getExpirationMs(), UserMapper.toResponse(user)),
                replacement);
    }

    @Transactional
    public void revoke(String rawToken) {
        tokenRepository.findByTokenHash(hash(rawToken)).ifPresent(token -> {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(Instant.now());
                tokenRepository.save(token);
            }
        });
    }

    public Duration getLifetime() {
        return lifetime;
    }

    private IssuedRefreshToken create(User user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now();

        RefreshToken token = new RefreshToken();
        token.setId(UUID.randomUUID());
        token.setUser(user);
        token.setTokenHash(hash(rawToken));
        token.setCreatedAt(now);
        token.setExpiresAt(now.plus(lifetime));
        tokenRepository.save(token);
        return new IssuedRefreshToken(token.getId(), rawToken, token.getExpiresAt());
    }

    private String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private ApiException invalidToken() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Refresh session is invalid or expired");
    }

    public record IssuedRefreshToken(UUID id, String value, Instant expiresAt) {}
    public record RefreshResult(AuthResponse auth, IssuedRefreshToken refreshToken) {}
}
