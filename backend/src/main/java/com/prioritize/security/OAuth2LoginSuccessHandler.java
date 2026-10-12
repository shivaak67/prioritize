package com.prioritize.security;

import java.io.IOException;
import java.net.URI;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.prioritize.config.OAuthProperties;
import com.prioritize.dto.AuthResponse;
import com.prioritize.service.GoogleAuthService;
import com.prioritize.service.RefreshTokenService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@ConditionalOnProperty(name = "app.oauth.google.enabled", havingValue = "true")
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final GoogleAuthService googleAuthService;
    private final OAuthProperties oauthProperties;
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenCookieService refreshTokenCookieService;

    public OAuth2LoginSuccessHandler(GoogleAuthService googleAuthService, OAuthProperties oauthProperties,
            RefreshTokenService refreshTokenService, RefreshTokenCookieService refreshTokenCookieService) {
        this.googleAuthService = googleAuthService;
        this.oauthProperties = oauthProperties;
        this.refreshTokenService = refreshTokenService;
        this.refreshTokenCookieService = refreshTokenCookieService;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException {
        if (!(authentication.getPrincipal() instanceof OidcUser oidcUser)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unsupported OAuth principal");
            return;
        }

        AuthResponse auth = googleAuthService.loginOrRegister(oidcUser);
        refreshTokenCookieService.write(response, refreshTokenService.issue(auth.user().id()));

        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }

        URI location = URI.create(oauthProperties.getSuccessRedirect());
        response.sendRedirect(location.toString());
    }
}
