package com.prioritize.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mockito.ArgumentCaptor;
import com.prioritize.config.AiProperties;
import com.prioritize.dto.*;
import com.prioritize.model.*;
import com.prioritize.repository.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(AssistantEventIntegrationTest.FixedTime.class)
class AssistantEventIntegrationTest {
    @TestConfiguration static class FixedTime {
        @Bean @Primary Clock eventTestClock() {
            return Clock.fixed(Instant.parse("2026-10-30T17:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired AssistantService assistant;
    @Autowired AiProperties properties;
    @Autowired UserRepository users;
    @Autowired CalendarEventRepository events;
    @Autowired CalendarEventService calendar;
    @Autowired TaskService tasks;
    @MockitoBean LlmClient llm;

    @Test void examRequestsRecoverFromInventedConflictsAndPreserveExplicitDatesAndRanges() {
        User user = new User(); user.setId(UUID.randomUUID()); user.setEmail(user.getId() + "@example.com");
        user.setFirstName("Exam"); user.setLastName("Test"); user.setAuthProvider(AuthProvider.LOCAL);
        user.setPasswordHash("unused"); user.setRole(Role.USER); UUID owner = users.save(user).getId();
        boolean enabled = properties.isEnabled(); String key = properties.getApiKey();
        properties.setEnabled(true); properties.setApiKey("test");
        try {
            calendar.create(owner, new CalendarEventRequest("Software Final", null, null,
                    Instant.parse("2026-12-09T16:00:00Z"), Instant.parse("2026-12-09T18:00:00Z"), false));
            calendar.create(owner, new CalendarEventRequest("Algorithms Final", null, null,
                    Instant.parse("2026-12-08T16:30:00Z"), Instant.parse("2026-12-08T18:30:00Z"), false));
            when(llm.complete(any(), any()))
                    .thenReturn(new LlmCompletion("I cannot create this because it conflicts with Software Final. Change the time?", List.of()))
                    .thenReturn(new LlmCompletion(null, List.of(new LlmToolCall("task", "create_task",
                            "{\"title\":\"Computer Org Final\",\"dueDate\":\"2026-12-09\",\"dueTime\":\"13:00\"}"))))
                    .thenReturn(new LlmCompletion(null, List.of(new LlmToolCall("event", "create_calendar_event",
                            "{\"title\":\"Computer Org Final\",\"startDate\":\"2026-11-04\",\"endDate\":\"2026-11-04\",\"startTime\":\"13:00\",\"endTime\":\"15:00\"}"))))
                    .thenReturn(new LlmCompletion("Created it on Tuesday December 9 at 10 AM", List.of()));
            String reply = assistant.chat(owner, new AssistantChatRequest(
                    "\"Computer Org Final\" due Wed, Dec. 9 · 1:00–3:00 p.m.", null, "America/Chicago")).reply();
            assertThat(reply).contains("Calendar event created", "Wednesday, December 9, 2026", "1:00 PM–3:00 PM").doesNotContain("conflict", "Tuesday");
            assertThat(tasks.list(owner, null, null, null)).isEmpty();

            when(llm.complete(any(), any()))
                    .thenReturn(new LlmCompletion("This conflicts with Algorithms Final on Tuesday December 9. Choose another day?", List.of()))
                    .thenReturn(new LlmCompletion(null, List.of(new LlmToolCall("linear", "create_calendar_event",
                            "{\"title\":\"Linear Algebra Final\",\"startDate\":\"2026-12-09\",\"startTime\":\"10:30\",\"endTime\":\"12:30\"}"))))
                    .thenReturn(new LlmCompletion("Saved Tuesday December 9", List.of()));
            String linear = assistant.chat(owner, new AssistantChatRequest(
                    "\"Linear Algebra Final\" due Thu, Dec. 10 · 10:30 a.m.–12:30 p.m.", null, "America/Chicago")).reply();
            assertThat(linear).contains("Thursday, December 10, 2026", "10:30 AM–12:30 PM").doesNotContain("conflict");
            var saved = calendar.list(owner, null, null);
            assertThat(saved).hasSize(4);
            assertThat(saved).anySatisfy(e -> {
                assertThat(e.title()).isEqualTo("Computer Org Final");
                assertThat(e.startAt()).isEqualTo(Instant.parse("2026-12-09T19:00:00Z"));
                assertThat(e.endAt()).isEqualTo(Instant.parse("2026-12-09T21:00:00Z"));
            }).anySatisfy(e -> {
                assertThat(e.title()).isEqualTo("Linear Algebra Final");
                assertThat(e.startAt()).isEqualTo(Instant.parse("2026-12-10T16:30:00Z"));
                assertThat(e.endAt()).isEqualTo(Instant.parse("2026-12-10T18:30:00Z"));
            });
        } finally {
            properties.setEnabled(enabled); properties.setApiKey(key);
            events.deleteAll(events.findByUserIdOrderByStartAtAsc(owner)); users.deleteById(owner);
        }
    }

    @Test @SuppressWarnings("unchecked")
    void phoneScreenSurvivesToolRetryPersistenceAndCalendarReadback() {
        User user = new User(); user.setId(UUID.randomUUID()); user.setEmail(user.getId() + "@example.com");
        user.setFirstName("Time"); user.setLastName("Test"); user.setAuthProvider(AuthProvider.LOCAL);
        user.setPasswordHash("unused"); user.setRole(Role.USER); UUID owner = users.save(user).getId();
        boolean enabled = properties.isEnabled(); String key = properties.getApiKey();
        properties.setEnabled(true); properties.setApiKey("test");
        try {
            when(llm.complete(any(), any()))
                    .thenReturn(new LlmCompletion(null, List.of(new LlmToolCall("bad", "create_calendar_event",
                            "{\"title\":\"Fedex Phone screen\",\"startAt\":\"2026-11-02T14:45:00-05:00\",\"endAt\":\"2026-11-02T15:15:00-05:00\"}"))))
                    .thenReturn(new LlmCompletion(null, List.of(new LlmToolCall("retry", "create_calendar_event",
                            "{\"title\":\"Fedex Phone screen\",\"startDate\":\"Monday\",\"startTime\":\"2:45 PM\",\"endTime\":\"3:15 PM\"}"))))
                    .thenReturn(new LlmCompletion("Scheduled Monday, November 2, 2:45–3:15 PM America/Chicago.", List.of()));
            var reply = assistant.chat(owner, new AssistantChatRequest(
                    "I have a Fedex Phone screen Monday from 2:45-3:15 PM", null, "America/Chicago"));
            assertThat(reply.reply()).contains("2:45 PM–3:15 PM");
            var saved = calendar.list(owner, Instant.parse("2026-11-02T06:00:00Z"), Instant.parse("2026-11-03T06:00:00Z"));
            assertThat(saved).hasSize(1);
            assertThat(saved.getFirst().startAt()).isEqualTo(Instant.parse("2026-11-02T20:45:00Z"));
            assertThat(saved.getFirst().endAt()).isEqualTo(Instant.parse("2026-11-02T21:15:00Z"));
            var messages = ArgumentCaptor.forClass(List.class);
            verify(llm, times(3)).complete(messages.capture(), any());
            List<Map<String, Object>> transcript = messages.getValue();
            assertThat(transcript.stream().filter(m -> "tool".equals(m.get("role"))).map(m -> m.get("content").toString()).toList())
                    .anySatisfy(s -> assertThat(s).contains("\"success\":false", "Offset does not match"))
                    .anySatisfy(s -> assertThat(s).contains("\"success\":true", "2026-11-02T14:45-06:00", "2026-11-02T15:15-06:00"));
        } finally {
            properties.setEnabled(enabled); properties.setApiKey(key);
            events.deleteAll(events.findByUserIdOrderByStartAtAsc(owner)); users.deleteById(owner);
        }
    }
}
