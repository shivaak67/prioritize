package com.prioritize.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prioritize.dto.*;

class AssistantEventTimeTest {
    private final UUID userId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final ZoneId zone = ZoneId.of("America/Chicago");
    private final ObjectMapper mapper = new ObjectMapper();
    private final CalendarEventService events = mock(CalendarEventService.class);
    private final AssistantToolExecutor executor = new AssistantToolExecutor(mapper,
            mock(TaskService.class), events, Clock.fixed(Instant.parse("2026-10-30T17:00:00Z"), ZoneOffset.UTC));

    @BeforeEach void echoSavedEvent() {
        when(events.create(eq(userId), any())).thenAnswer(call -> response(call.getArgument(1)));
        when(events.update(eq(userId), eq(eventId), any())).thenAnswer(call -> response(call.getArgument(2)));
    }

    private CalendarEventResponse response(CalendarEventRequest request) {
        return new CalendarEventResponse(eventId, null, request.title(), request.description(),
                request.startAt(), request.endAt(), false, null, null, null, null, null, null, false);
    }

    private String create(Map<String, String> fields) throws Exception {
        var args = new HashMap<>(fields);
        args.put("title", "Fedex Phone screen");
        return executor.execute(userId, "create_calendar_event", mapper.writeValueAsString(args), zone,
                "I have a Fedex Phone screen Monday from 2:45-3:15 PM");
    }

    @Test void incorrectAiOffsetIsRejectedBeforeSavingAndLocalRetryPreservesRequestedTime() throws Exception {
        assertThat(java.time.OffsetDateTime.parse("2026-11-02T14:45:00-05:00").atZoneSameInstant(zone).toLocalTime())
                .isEqualTo(LocalTime.of(13, 45)); // The original offset-trusting path reproduces the reported shift.
        String rejected = create(Map.of("startAt", "2026-11-02T14:45:00-05:00", "endAt", "2026-11-02T15:15:00-05:00"));
        assertThat(rejected).contains("\"success\":false", "Offset does not match");
        verify(events, never()).create(any(), any());

        String saved = create(Map.of("startDate", "Monday", "startTime", "2:45 PM", "endTime", "3:15 PM"));
        ArgumentCaptor<CalendarEventRequest> request = ArgumentCaptor.forClass(CalendarEventRequest.class);
        verify(events).create(eq(userId), request.capture());
        assertThat(request.getValue().startAt()).isEqualTo(Instant.parse("2026-11-02T20:45:00Z"));
        assertThat(request.getValue().endAt()).isEqualTo(Instant.parse("2026-11-02T21:15:00Z"));
        assertThat(saved).contains("\"success\":true", "2026-11-02T14:45-06:00", "2026-11-02T15:15-06:00", "America/Chicago");
    }

    @ParameterizedTest
    @CsvSource({"2026-07-06,2026-07-06T19:45:00Z", "2026-11-02,2026-11-02T20:45:00Z"})
    void derivesOffsetFromEventDate(String date, String utc) throws Exception {
        create(Map.of("startDate", date, "startTime", "14:45", "endTime", "15:15"));
        var request = ArgumentCaptor.forClass(CalendarEventRequest.class);
        verify(events).create(eq(userId), request.capture());
        assertThat(request.getValue().startAt()).isEqualTo(Instant.parse(utc));
    }

    @Test void explicitTimezoneIsRespectedAndResultUsesViewersZone() throws Exception {
        String result = create(Map.of("startDate", "Monday", "startTime", "14:45", "endTime", "15:15", "timeZone", "America/New_York"));
        var request = ArgumentCaptor.forClass(CalendarEventRequest.class);
        verify(events).create(eq(userId), request.capture());
        assertThat(request.getValue().startAt()).isEqualTo(Instant.parse("2026-11-02T19:45:00Z"));
        assertThat(result).contains("2026-11-02T13:45-06:00", "America/Chicago");
    }

    @ParameterizedTest
    @CsvSource({"2026-03-08,02:15,03:15,does not exist", "2026-11-01,01:15,01:45,occurs twice"})
    void asksForClarificationBeforeSavingDstEdgeCases(String date, String start, String end, String error) throws Exception {
        assertThat(create(Map.of("startDate", date, "startTime", start, "endTime", end)))
                .contains("\"success\":false", error, "Ask the user");
        verify(events, never()).create(any(), any());
    }

    @Test void repeatedTimeCanUseOccurrenceChosenByUser() throws Exception {
        create(Map.of("startDate", "2026-11-01", "startTime", "01:15", "endTime", "01:45",
                "startOffset", "-06:00", "endOffset", "-06:00"));
        var request = ArgumentCaptor.forClass(CalendarEventRequest.class);
        verify(events).create(eq(userId), request.capture());
        assertThat(request.getValue().startAt()).isEqualTo(Instant.parse("2026-11-01T07:15:00Z"));
    }

    @Test void movingEventAcrossDstPreservesLocalTimesAndOvernightDayOffset() throws Exception {
        when(events.get(userId, eventId)).thenReturn(response(new CalendarEventRequest("Overnight", null, null,
                Instant.parse("2026-10-31T04:30:00Z"), Instant.parse("2026-10-31T05:30:00Z"), false)));
        String result = executor.execute(userId, "update_calendar_event", mapper.writeValueAsString(
                Map.of("eventId", eventId.toString(), "startDate", "Monday")), zone);
        var request = ArgumentCaptor.forClass(CalendarEventRequest.class);
        verify(events).update(eq(userId), eq(eventId), request.capture());
        assertThat(request.getValue().startAt()).isEqualTo(Instant.parse("2026-11-03T05:30:00Z"));
        assertThat(request.getValue().endAt()).isEqualTo(Instant.parse("2026-11-03T06:30:00Z"));
        assertThat(result).contains("\"success\":true");
    }

    @Test void titleOnlyUpdatePreservesRepeatedTimeWithoutAskingAgain() throws Exception {
        when(events.get(userId, eventId)).thenReturn(response(new CalendarEventRequest("Old", null, null,
                Instant.parse("2026-11-01T07:15:00Z"), Instant.parse("2026-11-01T07:45:00Z"), false)));
        executor.execute(userId, "update_calendar_event", mapper.writeValueAsString(
                Map.of("eventId", eventId.toString(), "title", "New")), zone);
        var request = ArgumentCaptor.forClass(CalendarEventRequest.class);
        verify(events).update(eq(userId), eq(eventId), request.capture());
        assertThat(request.getValue().startAt()).isEqualTo(Instant.parse("2026-11-01T07:15:00Z"));
    }

    @Test void relativeDateUsesRequestZoneNearMidnight() throws Exception {
        var nearMidnight = new AssistantToolExecutor(mapper, mock(TaskService.class), events,
                Clock.fixed(Instant.parse("2026-11-02T01:00:00Z"), ZoneOffset.UTC));
        nearMidnight.execute(userId, "create_calendar_event", mapper.writeValueAsString(Map.of(
                "title", "Call", "startDate", "today", "startTime", "20:00", "endTime", "20:30")), zone);
        var request = ArgumentCaptor.forClass(CalendarEventRequest.class);
        verify(events).create(eq(userId), request.capture());
        assertThat(request.getValue().startAt()).isEqualTo(Instant.parse("2026-11-02T02:00:00Z"));
    }

    @ParameterizedTest
    @CsvSource({"Monday,2026-11-02", "next Monday,2026-11-09"})
    void bareWeekdayIncludesTodayButNextWeekdaySkipsToday(String date, String expected) throws Exception {
        var onMonday = new AssistantToolExecutor(mapper, mock(TaskService.class), events,
                Clock.fixed(Instant.parse("2026-11-02T15:00:00Z"), ZoneOffset.UTC));
        onMonday.execute(userId, "create_calendar_event", mapper.writeValueAsString(Map.of(
                "title", "Call", "startDate", date, "startTime", "14:45", "endTime", "15:15")), zone);
        var request = ArgumentCaptor.forClass(CalendarEventRequest.class);
        verify(events).create(eq(userId), request.capture());
        assertThat(request.getValue().startAt().atZone(zone).toLocalDate()).isEqualTo(LocalDate.parse(expected));
    }

    @Test void invalidOffsetDuringUpdateDoesNotChangeExistingEvent() throws Exception {
        when(events.get(userId, eventId)).thenReturn(response(new CalendarEventRequest("Call", null, null,
                Instant.parse("2026-11-02T20:45:00Z"), Instant.parse("2026-11-02T21:15:00Z"), false)));
        String result = executor.execute(userId, "update_calendar_event", mapper.writeValueAsString(Map.of(
                "eventId", eventId.toString(), "startAt", "2026-11-02T14:45:00-05:00")), zone);
        assertThat(result).contains("\"success\":false", "Offset does not match");
        verify(events, never()).update(any(), any(), any());
    }
}
