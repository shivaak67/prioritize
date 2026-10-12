package com.prioritize.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import jakarta.servlet.http.Cookie;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void registerLoginMeAndLogoutFlow() throws Exception {
        String registerBody = """
                {
                  "firstName": "Grace",
                  "lastName": "Hopper",
                  "email": "grace@example.com",
                  "password": "password1",
                  "passwordConfirmation": "password1"
                }
                """;

        MvcResult registerResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(900000))
                .andExpect(jsonPath("$.user.email").value("grace@example.com"))
                .andExpect(jsonPath("$.user.authProvider").value("LOCAL"))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andReturn();

        String registerToken = objectMapper.readTree(registerResult.getResponse().getContentAsString())
                .path("accessToken")
                .asText();
        assertThat(registerToken).isNotBlank();

        String loginBody = """
                {
                  "email": "grace@example.com",
                  "password": "password1"
                }
                """;

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.firstName").value("Grace"))
                .andReturn();

        String loginToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .path("accessToken")
                .asText();

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + loginToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("grace@example.com"))
                .andExpect(jsonPath("$.lastName").value("Hopper"));

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + loginToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void refreshCookieRotatesAndLogoutRevokesSession() throws Exception {
        String registerBody = """
                {
                  "firstName": "Remembered",
                  "lastName": "User",
                  "email": "remembered@example.com",
                  "password": "password1",
                  "passwordConfirmation": "password1"
                }
                """;

        MvcResult register = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andReturn();
        Cookie firstCookie = register.getResponse().getCookie("prioritize_refresh");
        assertThat(firstCookie).isNotNull();
        assertThat(firstCookie.isHttpOnly()).isTrue();

        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh").cookie(firstCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();
        Cookie rotatedCookie = refreshed.getResponse().getCookie("prioritize_refresh");
        assertThat(rotatedCookie).isNotNull();
        assertThat(rotatedCookie.getValue()).isNotEqualTo(firstCookie.getValue());

        mockMvc.perform(post("/api/auth/refresh").cookie(firstCookie))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/logout").cookie(rotatedCookie))
                .andExpect(status().isNoContent())
                .andExpect(result -> assertThat(result.getResponse()
                        .getCookie("prioritize_refresh").getMaxAge()).isZero());

        mockMvc.perform(post("/api/auth/refresh").cookie(rotatedCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registerValidationFailureReturns400() throws Exception {
        String body = """
                {
                  "firstName": "",
                  "lastName": "Hopper",
                  "email": "not-an-email",
                  "password": "short",
                  "passwordConfirmation": "short"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        String registerBody = """
                {
                  "firstName": "Alan",
                  "lastName": "Turing",
                  "email": "alan@example.com",
                  "password": "password1",
                  "passwordConfirmation": "password1"
                }
                """;
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated());

        String loginBody = """
                {
                  "email": "alan@example.com",
                  "password": "wrong-password"
                }
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void duplicateEmailReturns400() throws Exception {
        String body = """
                {
                  "firstName": "Duplicate",
                  "lastName": "User",
                  "email": "dup@example.com",
                  "password": "password1",
                  "passwordConfirmation": "password1"
                }
                """;
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email is already registered"));
    }

    @Test
    void passwordConfirmationMismatchReturns400() throws Exception {
        String body = """
                {
                  "firstName": "Mismatch",
                  "lastName": "Case",
                  "email": "mismatch@example.com",
                  "password": "password1",
                  "passwordConfirmation": "password2"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void jwtRoundTripProducesValidClaims() throws Exception {
        String body = """
                {
                  "firstName": "Token",
                  "lastName": "User",
                  "email": "token@example.com",
                  "password": "password1",
                  "passwordConfirmation": "password1"
                }
                """;
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = root.path("accessToken").asText();
        String userId = root.path("user").path("id").asText();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId));
    }
}
