package com.prioritize.controller;

import java.time.Instant;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.prioritize.dto.InsightsSummaryResponse;
import com.prioritize.security.CurrentUserService;
import com.prioritize.service.InsightsService;

@RestController
@RequestMapping("/api/insights")
public class InsightsController {

    private final InsightsService insightsService;
    private final CurrentUserService currentUserService;

    public InsightsController(InsightsService insightsService, CurrentUserService currentUserService) {
        this.insightsService = insightsService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/summary")
    public InsightsSummaryResponse summary(
            @RequestParam Instant from, @RequestParam Instant to,
            @RequestParam(defaultValue = "UTC") String timeZone) {
        UUID userId = currentUserService.requireCurrentUserId();
        try {
            return insightsService.summary(userId, from, to, java.time.ZoneId.of(timeZone));
        } catch (java.time.DateTimeException ex) {
            throw new com.prioritize.exception.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid time zone");
        }
    }
}
