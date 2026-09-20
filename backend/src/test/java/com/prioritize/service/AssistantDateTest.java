package com.prioritize.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prioritize.config.AiProperties;
import com.prioritize.dto.*;
import com.prioritize.exception.ApiException;

class AssistantDateTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-06T01:30:00Z"), ZoneOffset.UTC);
    private final UUID userId = UUID.randomUUID();
    private final LlmClient llm = mock(LlmClient.class);
    private final TaskService tasks = mock(TaskService.class);
    private final CalendarEventService events = mock(CalendarEventService.class);
    private final DashboardService dashboard = mock(DashboardService.class);
    private final AiProperties properties = new AiProperties();
    private final AssistantService service = new AssistantService(properties, llm,
            mock(AssistantToolExecutor.class), tasks, events, dashboard, clock);

    @Test
    void answersFromClockInUsersZoneEvenWithIncorrectHistoryAndAiEnabled() {
        properties.setEnabled(true);
        properties.setApiKey("test");
        var request = new AssistantChatRequest("whats the date today",
                List.of(new AssistantMessageDto("assistant", "Today is September 6")), "America/Chicago");
        assertThat(service.chat(userId, request).reply()).isEqualTo("Today's date is Saturday, September 5, 2026.");
        verifyNoInteractions(llm, tasks, dashboard);
    }

    @Test
    void respectsDateAcrossTimezonesAndFallsBackToUtcForOldClients() {
        assertThat(service.chat(userId, new AssistantChatRequest("What is today's date?", null, "Asia/Tokyo")).reply())
                .contains("Sunday, September 6, 2026");
        assertThat(service.chat(userId, new AssistantChatRequest("date today", null, null)).reply())
                .contains("Sunday, September 6, 2026");
        assertThatThrownBy(() -> service.chat(userId, new AssistantChatRequest("date today", null, "invalid")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void modelContextContainsCurrentLocalDateAndZone() {
        properties.setEnabled(true);
        properties.setApiKey("test");
        when(dashboard.summary(userId, ZoneId.of("America/Chicago"))).thenReturn(mock(DashboardSummaryResponse.class));
        when(llm.complete(any(), any())).thenReturn(new LlmCompletion("Ready", List.of()));
        service.chat(userId, new AssistantChatRequest("Help plan tomorrow", null, "America/Chicago"));
        ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass(List.class);
        verify(llm).complete(messages.capture(), any());
        assertThat(messages.getValue().get(0).get("content").toString())
                .contains("Today key: 2026-09-05", "User timezone: America/Chicago");
    }

    @Test
    void toolResolvesTodayUsingRequestTimezone() {
        var executor = new AssistantToolExecutor(new ObjectMapper(), tasks, events, clock);
        // Capture the request even though the mocked service returns no response.
        executor.execute(userId, "create_task", "{\"title\":\"Study\",\"dueDate\":\"today\"}", ZoneId.of("America/Chicago"));
        ArgumentCaptor<TaskRequest> request = ArgumentCaptor.forClass(TaskRequest.class);
        verify(tasks).create(eq(userId), request.capture());
        assertThat(request.getValue().dueDate()).isEqualTo(LocalDate.of(2026, 9, 5));
    }

    @Test
    @SuppressWarnings("unchecked")
    void calendarContextKeepsLocalDatesAcrossMidnightAndChecksAllLists() {
        properties.setEnabled(true); properties.setApiKey("test");
        when(dashboard.summary(userId, ZoneId.of("America/Chicago"))).thenReturn(mock(DashboardSummaryResponse.class));
        var event = new CalendarEventResponse(UUID.randomUUID(), null, "Late study", null,
                Instant.parse("2026-09-07T04:30:00Z"), Instant.parse("2026-09-07T05:30:00Z"),
                false, null, null, null, null, null, null, false);
        when(events.list(userId, null, null)).thenReturn(List.of(event));
        when(llm.complete(any(), any())).thenReturn(new LlmCompletion("Ready", List.of()));
        service.chat(userId, new AssistantChatRequest("Any duplicates tomorrow?", null, "America/Chicago"));
        ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass(List.class);
        verify(llm).complete(messages.capture(), any());
        assertThat(messages.getValue().getFirst().get("content").toString())
                .contains("Tomorrow key: 2026-09-06", "from 2026-09-06 11:30 PM -05:00 to 2026-09-07 12:30 AM -05:00",
                        "check all three lists", "Reminder notification times are not assignment deadlines");
    }

    @Test
    void calendarToolInterpretsLocalTimeInUsersZone() {
        var executor = new AssistantToolExecutor(new ObjectMapper(), tasks, events, clock);
        executor.execute(userId, "create_calendar_event",
                "{\"title\":\"Study\",\"startAt\":\"2026-09-05T16:00:00\",\"endAt\":\"2026-09-05T17:00:00\"}",
                ZoneId.of("America/Chicago"));
        ArgumentCaptor<CalendarEventRequest> request = ArgumentCaptor.forClass(CalendarEventRequest.class);
        verify(events).create(eq(userId), request.capture());
        assertThat(request.getValue().startAt()).isEqualTo(Instant.parse("2026-09-05T21:00:00Z"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dailyFocusContextExplicitlyIncludesQuizAndCompletionEvenAfterItsTimestamp() {
        properties.setEnabled(true); properties.setApiKey("test");
        when(dashboard.summary(userId, ZoneId.of("America/Chicago"))).thenReturn(mock(DashboardSummaryResponse.class));
        var quiz = new CalendarEventResponse(UUID.randomUUID(), null, "Quiz 3", null,
                Instant.parse("2026-09-05T00:00:00Z"), Instant.parse("2026-09-06T00:00:00Z"),
                true, null, null, "DEADLINE", null, LocalDate.parse("2026-09-05"), LocalDate.parse("2026-09-06"), false);
        var done = new CalendarEventResponse(UUID.randomUUID(), null, "Completed quiz", null,
                quiz.startAt(), quiz.endAt(), true, null, null, "DEADLINE", null,
                quiz.canvasStartDate(), quiz.canvasEndDate(), true);
        when(events.list(userId, null, null)).thenReturn(List.of(quiz, done));
        when(llm.complete(any(), any())).thenReturn(new LlmCompletion("Quiz 3 is due today", List.of()));
        service.chat(userId, new AssistantChatRequest("What should I focus on today?", null, "America/Chicago"));
        ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass(List.class);
        verify(llm).complete(messages.capture(), any());
        assertThat(messages.getValue().getFirst().get("content").toString())
                .contains("Quiz 3 [DUE TODAY, due 2026-09-05 (time not provided by Canvas; do not assume midnight or 11:59 PM)]",
                        "Completed quiz [COMPLETED, due 2026-09-05 (time not provided by Canvas; do not assume midnight or 11:59 PM)]",
                        "always check unfinished Canvas assignments due today");
    }
}
