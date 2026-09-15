package com.prioritize.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import com.prioritize.dto.InsightsSummaryResponse;
import com.prioritize.exception.ApiException;
import com.prioritize.model.TaskStatus;
import com.prioritize.repository.TaskRepository;
import com.prioritize.repository.TimeEntryRepository;

@ExtendWith(MockitoExtension.class)
class InsightsServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-01-08T00:00:00Z");
    private static final Set<TaskStatus> OPEN =
            EnumSet.of(TaskStatus.TODO, TaskStatus.IN_PROGRESS);

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TimeEntryRepository timeEntryRepository;

    @Mock
    private com.prioritize.repository.CalendarEventRepository calendarEventRepository;

    private InsightsService insightsService;

    @BeforeEach
    void setUp() {
        insightsService = new InsightsService(taskRepository, timeEntryRepository, calendarEventRepository);
    }

    @Test
    void summaryAggregatesCountsMinutesAndRates() {
        UUID taskA = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID taskB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        when(taskRepository.countByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        USER_ID, FROM, TO))
                .thenReturn(4L);
        when(taskRepository.countByUserIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
                        USER_ID, FROM, TO))
                .thenReturn(2L);
        when(taskRepository.countByUserIdAndStatusIn(USER_ID, OPEN)).thenReturn(3L);
        when(taskRepository.sumEstimatedMinutesByUserIdAndStatusIn(USER_ID, OPEN)).thenReturn(90L);
        when(timeEntryRepository.sumDurationByUserIdAndCreatedAtInWindow(USER_ID, FROM, TO))
                .thenReturn(150L);
        when(timeEntryRepository.findCreatedAtAndDurationInWindow(USER_ID, FROM, TO))
                .thenReturn(List.of(
                        new Object[] {Instant.parse("2026-01-02T10:00:00Z"), 40},
                        new Object[] {Instant.parse("2026-01-02T15:00:00Z"), 20},
                        new Object[] {Instant.parse("2026-01-05T09:00:00Z"), 90}));
        when(timeEntryRepository.findTopTasksByMinutesInWindow(
                        eq(USER_ID), eq(FROM), eq(TO), any(Pageable.class)))
                .thenReturn(List.of(
                        new Object[] {taskA, "Essay", 100L},
                        new Object[] {taskB, "Lab", 50L}));
        when(timeEntryRepository.findTopCategoriesByMinutesInWindow(USER_ID, FROM, TO))
                .thenReturn(List.<Object[]>of(new Object[] {"Computer Org", 120L}));
        when(taskRepository.findFiltered(USER_ID, null, null, null)).thenReturn(List.of());

        InsightsSummaryResponse summary = insightsService.summary(USER_ID, FROM, TO);

        assertThat(summary.from()).isEqualTo(FROM);
        assertThat(summary.to()).isEqualTo(TO);
        assertThat(summary.tasksCreated()).isEqualTo(4);
        assertThat(summary.tasksCompleted()).isEqualTo(2);
        assertThat(summary.openTasks()).isEqualTo(3);
        assertThat(summary.totalMinutesLogged()).isEqualTo(150);
        assertThat(summary.estimatedMinutesOpen()).isEqualTo(90);
        assertThat(summary.completionRate()).isEqualTo(0.5);
        assertThat(summary.weeklyTasksDue()).isZero();
        assertThat(summary.weeklyTasksCompleted()).isZero();
        assertThat(summary.focusStreakDays()).isZero();
        assertThat(summary.mostProductiveDay()).isEqualTo("Monday");
        assertThat(summary.topCategoryName()).isEqualTo("Computer Org");
        assertThat(summary.minutesByDay())
                .containsExactly(
                        new InsightsSummaryResponse.MinutesByDay(LocalDate.of(2026, 1, 2), 60),
                        new InsightsSummaryResponse.MinutesByDay(LocalDate.of(2026, 1, 5), 90));
        assertThat(summary.topTasksByMinutes())
                .containsExactly(
                        new InsightsSummaryResponse.TaskMinutes(taskA, "Essay", 100),
                        new InsightsSummaryResponse.TaskMinutes(taskB, "Lab", 50));

        verify(timeEntryRepository)
                .findTopTasksByMinutesInWindow(eq(USER_ID), eq(FROM), eq(TO), any(Pageable.class));
    }

    @Test
    void completionRateUsesMaxCreatedOfOneWhenNoneCreated() {
        stubEmptyAggregates();
        when(taskRepository.countByUserIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
                        USER_ID, FROM, TO))
                .thenReturn(1L);

        InsightsSummaryResponse summary = insightsService.summary(USER_ID, FROM, TO);

        assertThat(summary.tasksCreated()).isZero();
        assertThat(summary.tasksCompleted()).isEqualTo(1);
        assertThat(summary.completionRate()).isEqualTo(1.0);
    }

    @Test
    void rejectsNullFromOrTo() {
        assertThatThrownBy(() -> insightsService.summary(USER_ID, null, TO))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessage("from and to are required");

        assertThatThrownBy(() -> insightsService.summary(USER_ID, FROM, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessage("from and to are required");
    }

    @Test
    void rejectsToNotAfterFrom() {
        assertThatThrownBy(() -> insightsService.summary(USER_ID, FROM, FROM))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessage("to must be after from");

        assertThatThrownBy(() -> insightsService.summary(USER_ID, TO, FROM))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessage("to must be after from");
    }

    @Test
    void rejectsWindowLongerThan366Days() {
        Instant farTo = FROM.plusSeconds(367L * 24 * 60 * 60);

        assertThatThrownBy(() -> insightsService.summary(USER_ID, FROM, farTo))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessage("insights window cannot exceed 366 days");
    }

    @Test
    void allowsWindowOfExactly366Days() {
        Instant exactly366 = FROM.plusSeconds(366L * 24 * 60 * 60);
        stubEmptyAggregates(FROM, exactly366);

        InsightsSummaryResponse summary = insightsService.summary(USER_ID, FROM, exactly366);

        assertThat(summary.from()).isEqualTo(FROM);
        assertThat(summary.to()).isEqualTo(exactly366);
        assertThat(summary.completionRate()).isZero();
    }

    private void stubEmptyAggregates() {
        stubEmptyAggregates(FROM, TO);
    }

    @Test
    void includesCanvasDeadlinesAndClipsEventsWithoutInflatingFocus() {
        stubEmptyAggregates();
        var deadline = event("DEADLINE", FROM.plusSeconds(60), FROM.plusSeconds(61));
        deadline.setCanvasCompleted(true);
        var dateOnly = event("DEADLINE", FROM.minusSeconds(3600), FROM.plusSeconds(1));
        dateOnly.setAllDay(true);
        dateOnly.setCanvasStartDate(LocalDate.of(2026, 1, 1));
        var outside = event("DEADLINE", TO, TO.plusSeconds(1));
        var manual = event(null, FROM.minusSeconds(3600), FROM.plusSeconds(1800));
        var canvas = event("EVENT", TO.minusSeconds(3600), TO.plusSeconds(3600));
        var allDay = event("EVENT", FROM, TO);
        allDay.setAllDay(true);
        when(calendarEventRepository.findByUserIdOrderByStartAtAsc(USER_ID))
                .thenReturn(List.of(deadline, dateOnly, outside, manual, canvas, allDay));
        var result = insightsService.summary(USER_ID, FROM, TO, java.time.ZoneId.of("UTC"));
        assertThat(result.weeklyTasksDue()).isEqualTo(5);
        assertThat(result.weeklyTasksCompleted()).isEqualTo(4);
        assertThat(result.canvasAssignmentsDue()).isEqualTo(2);
        assertThat(result.canvasAssignmentsCompleted()).isEqualTo(1);
        assertThat(result.calendarEvents()).isEqualTo(3);
        assertThat(result.canvasEvents()).isEqualTo(2);
        assertThat(result.scheduledMinutes()).isEqualTo(90);
        assertThat(result.totalMinutesLogged()).isZero();
        verify(calendarEventRepository).findByUserIdOrderByStartAtAsc(USER_ID);
    }

    private com.prioritize.model.CalendarEvent event(String kind, Instant start, Instant end) {
        var event = new com.prioritize.model.CalendarEvent();
        event.setUserId(USER_ID);
        event.setCanvasKind(kind);
        event.setStartAt(start);
        event.setEndAt(end);
        return event;
    }

    @Test
    void futureManualAndCanvasEventsIncreaseTotalButNotProgress() {
        LocalDate tomorrow = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1);
        Instant from = tomorrow.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Instant to = from.plusSeconds(7 * 86400);
        stubEmptyAggregates(from, to);
        var manual = event(null, from.plusSeconds(60), from.plusSeconds(3600));
        var canvas = event("EVENT", from, from.plusSeconds(86400));
        canvas.setAllDay(true);
        canvas.setCanvasStartDate(tomorrow);
        canvas.setCanvasEndDate(tomorrow.plusDays(1));
        when(calendarEventRepository.findByUserIdOrderByStartAtAsc(USER_ID)).thenReturn(List.of(manual, canvas));
        var result = insightsService.summary(USER_ID, from, to, java.time.ZoneOffset.UTC);
        assertThat(result.weeklyTasksDue()).isEqualTo(2);
        assertThat(result.weeklyTasksCompleted()).isZero();
    }

    @Test
    void usesLocalWeekAndEffectiveDeadlineOverridesAndExcludesCancelledTasks() {
        Instant from = Instant.parse("2026-09-14T05:00:00Z");
        Instant to = Instant.parse("2026-09-21T05:00:00Z");
        stubEmptyAggregates(from, to);
        var task = new com.prioritize.model.Task();
        task.setDueDate(LocalDate.of(2026, 9, 14));
        task.setStatus(TaskStatus.COMPLETED);
        var cancelled = new com.prioritize.model.Task();
        cancelled.setDueDate(LocalDate.of(2026, 9, 14));
        cancelled.setStatus(TaskStatus.CANCELLED);
        when(taskRepository.findFiltered(USER_ID, null, null, null)).thenReturn(List.of(task, cancelled));
        var overridden = event("DEADLINE", to, to.plusSeconds(1));
        overridden.setCanvasStartDate(LocalDate.of(2026, 9, 20));
        overridden.setCanvasDueTime(java.time.LocalTime.of(23, 59));
        overridden.setCanvasDueZone("America/Chicago");
        overridden.setCanvasCompleted(true);
        var nextWeek = event("DEADLINE", to.minusSeconds(3600), to);
        nextWeek.setAllDay(true);
        nextWeek.setCanvasStartDate(LocalDate.of(2026, 9, 21));
        when(calendarEventRepository.findByUserIdOrderByStartAtAsc(USER_ID)).thenReturn(List.of(overridden, nextWeek));
        var result = insightsService.summary(USER_ID, from, to, java.time.ZoneId.of("America/Chicago"));
        assertThat(result.weeklyTasksDue()).isEqualTo(2);
        assertThat(result.weeklyTasksCompleted()).isEqualTo(2);
        assertThat(result.canvasAssignmentsDue()).isEqualTo(1);
        assertThat(result.calendarEvents()).isZero();
    }

    private void stubEmptyAggregates(Instant from, Instant to) {
        when(taskRepository.countByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        USER_ID, from, to))
                .thenReturn(0L);
        when(taskRepository.countByUserIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
                        USER_ID, from, to))
                .thenReturn(0L);
        when(taskRepository.countByUserIdAndStatusIn(USER_ID, OPEN)).thenReturn(0L);
        when(taskRepository.sumEstimatedMinutesByUserIdAndStatusIn(USER_ID, OPEN)).thenReturn(0L);
        when(timeEntryRepository.sumDurationByUserIdAndCreatedAtInWindow(USER_ID, from, to))
                .thenReturn(0L);
        when(timeEntryRepository.findCreatedAtAndDurationInWindow(USER_ID, from, to))
                .thenReturn(List.of());
        when(timeEntryRepository.findTopTasksByMinutesInWindow(
                        eq(USER_ID), eq(from), eq(to), any(Pageable.class)))
                .thenReturn(List.of());
        when(timeEntryRepository.findTopCategoriesByMinutesInWindow(USER_ID, from, to))
                .thenReturn(List.of());
        when(taskRepository.findFiltered(USER_ID, null, null, null)).thenReturn(List.of());
    }
}
