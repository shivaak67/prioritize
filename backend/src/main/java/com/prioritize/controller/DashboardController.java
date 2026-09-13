package com.prioritize.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.prioritize.dto.DashboardSummaryResponse;
import com.prioritize.security.CurrentUserService;
import com.prioritize.service.DashboardService;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final CurrentUserService currentUserService;

    public DashboardController(DashboardService dashboardService, CurrentUserService currentUserService) {
        this.dashboardService = dashboardService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/summary")
    public DashboardSummaryResponse summary(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "UTC") String timeZone) {
        try {
            return dashboardService.summary(currentUserService.requireCurrentUserId(), java.time.ZoneId.of(timeZone));
        } catch (java.time.DateTimeException ex) {
            throw new com.prioritize.exception.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid timezone");
        }
    }
}
