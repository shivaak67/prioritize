package com.prioritize.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.prioritize.dto.InsightsSummaryResponse;
import com.prioritize.exception.ApiException;
import com.prioritize.model.Task;
import com.prioritize.model.CalendarEvent;
import com.prioritize.repository.CalendarEventRepository;
import com.prioritize.model.TaskStatus;
import com.prioritize.repository.TaskRepository;
import com.prioritize.repository.TimeEntryRepository;

/**
 * Read-only analytics: aggregate counts and minutes only — no recommendations or auto-priority.
 */
@Service
@Transactional(readOnly = true)
public class InsightsService {

    private static final int MAX_WINDOW_DAYS = 366;
    private static final int TOP_TASKS_LIMIT = 5;
    private static final ZoneId DISPLAY_ZONE = ZoneId.systemDefault();
    private static final Set<TaskStatus> OPEN_STATUSES =
            EnumSet.of(TaskStatus.TODO, TaskStatus.IN_PROGRESS);

    private final TaskRepository taskRepository;
    private final TimeEntryRepository timeEntryRepository;
    private final CalendarEventRepository calendarEventRepository;

    public InsightsService(TaskRepository taskRepository, TimeEntryRepository timeEntryRepository,
            CalendarEventRepository calendarEventRepository) {
        this.taskRepository = taskRepository;
        this.timeEntryRepository = timeEntryRepository;
        this.calendarEventRepository = calendarEventRepository;
    }

    public InsightsSummaryResponse summary(UUID userId, Instant from, Instant to) {
        return summary(userId, from, to, DISPLAY_ZONE);
    }

    public InsightsSummaryResponse summary(UUID userId, Instant from, Instant to, ZoneId zone) {
        validateWindow(from, to);

        // Half-open window [from, to) for created/completed/logged aggregations.
        int tasksCreated = toInt(taskRepository.countByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                userId, from, to));
        int tasksCompleted =
                toInt(taskRepository.countByUserIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
                        userId, from, to));

        // openTasks / estimatedMinutesOpen: current snapshot (not bound to the from/to window).
        int openTasks = toInt(taskRepository.countByUserIdAndStatusIn(userId, OPEN_STATUSES));
        int estimatedMinutesOpen =
                toInt(taskRepository.sumEstimatedMinutesByUserIdAndStatusIn(userId, OPEN_STATUSES));

        int totalMinutesLogged =
                toInt(timeEntryRepository.sumDurationByUserIdAndCreatedAtInWindow(userId, from, to));

        double completionRate = (double) tasksCompleted / Math.max(tasksCreated, 1);

        List<InsightsSummaryResponse.MinutesByDay> minutesByDay = buildMinutesByDay(userId, from, to, zone);
        List<InsightsSummaryResponse.TaskMinutes> topTasks = buildTopTasks(userId, from, to);

        List<Task> tasks = taskRepository.findFiltered(userId, null, null, null);
        LocalDate today = LocalDate.now(zone);
        LocalDate weekStart = from.atZone(zone).toLocalDate();
        LocalDate weekEnd = to.atZone(zone).toLocalDate();

        int weeklyTasksDue = 0;
        int weeklyTasksCompleted = 0;
        for (Task task : tasks) {
            if (task.getStatus() == TaskStatus.CANCELLED) continue;
            if (task.getDueDate() == null) {
                continue;
            }
            if (task.getDueDate().isBefore(weekStart) || !task.getDueDate().isBefore(weekEnd)) {
                continue;
            }
            weeklyTasksDue++;
            if (task.getStatus() == TaskStatus.COMPLETED) {
                weeklyTasksCompleted++;
            }
        }

        int canvasAssignmentsDue = 0;
        int canvasAssignmentsCompleted = 0;
        int calendarEvents = 0;
        int canvasEvents = 0;
        int elapsedEvents = 0;
        Instant now = Instant.now();
        long scheduledMinutes = 0;
        for (CalendarEvent event : calendarEventRepository.findByUserIdOrderByStartAtAsc(userId)) {
            if ("DEADLINE".equals(event.getCanvasKind())) {
                boolean inWindow = event.isAllDay() && event.getCanvasStartDate() != null
                        ? !event.getCanvasStartDate().isBefore(weekStart) && event.getCanvasStartDate().isBefore(weekEnd)
                        : !event.getStartAt().isBefore(from) && event.getStartAt().isBefore(to);
                if (inWindow) {
                    canvasAssignmentsDue++;
                    if (event.isCanvasCompleted()) canvasAssignmentsCompleted++;
                }
            } else {
                boolean overlaps = event.isAllDay() && event.getCanvasStartDate() != null
                        ? event.getCanvasStartDate().isBefore(weekEnd)
                            && (event.getCanvasEndDate() == null ? event.getCanvasStartDate().plusDays(1) : event.getCanvasEndDate()).isAfter(weekStart)
                        : event.getStartAt().isBefore(to) && event.getEndAt().isAfter(from);
                if (!overlaps) continue;
                calendarEvents++;
                boolean elapsed = event.isAllDay() && event.getCanvasStartDate() != null
                        ? !(event.getCanvasEndDate() == null ? event.getCanvasStartDate().plusDays(1) : event.getCanvasEndDate()).isAfter(today)
                        : !event.getEndAt().isAfter(now);
                if (elapsed) elapsedEvents++;
                if ("EVENT".equals(event.getCanvasKind())) canvasEvents++;
                if (!event.isAllDay()) {
                    Instant start = event.getStartAt().isBefore(from) ? from : event.getStartAt();
                    Instant end = event.getEndAt().isAfter(to) ? to : event.getEndAt();
                    scheduledMinutes += Math.max(0, ChronoUnit.MINUTES.between(start, end));
                }
            }
        }
        weeklyTasksDue += canvasAssignmentsDue + calendarEvents;
        weeklyTasksCompleted += canvasAssignmentsCompleted + elapsedEvents;

        int focusStreakDays = computeFocusStreak(tasks, minutesByDay, today, zone);
        String mostProductiveDay = findMostProductiveDay(minutesByDay);
        String topCategoryName = findTopCategory(userId, from, to);

        return new InsightsSummaryResponse(
                from,
                to,
                tasksCreated,
                tasksCompleted,
                openTasks,
                totalMinutesLogged,
                estimatedMinutesOpen,
                completionRate,
                weeklyTasksDue,
                weeklyTasksCompleted,
                canvasAssignmentsDue,
                canvasAssignmentsCompleted,
                calendarEvents,
                canvasEvents,
                toInt(scheduledMinutes),
                focusStreakDays,
                mostProductiveDay,
                topCategoryName,
                minutesByDay,
                topTasks);
    }

    private int computeFocusStreak(
            List<Task> tasks,
            List<InsightsSummaryResponse.MinutesByDay> minutesByDay,
            LocalDate today, ZoneId zone) {
        Set<LocalDate> activeDays = new HashSet<>();
        for (InsightsSummaryResponse.MinutesByDay day : minutesByDay) {
            if (day.minutes() > 0) {
                activeDays.add(day.date());
            }
        }
        for (Task task : tasks) {
            if (task.getCompletedAt() != null) {
                activeDays.add(LocalDate.ofInstant(task.getCompletedAt(), zone));
            }
        }

        int streak = 0;
        LocalDate cursor = today;
        while (activeDays.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    private String findMostProductiveDay(List<InsightsSummaryResponse.MinutesByDay> minutesByDay) {
        if (minutesByDay.isEmpty()) {
            return null;
        }
        InsightsSummaryResponse.MinutesByDay best = minutesByDay.get(0);
        for (InsightsSummaryResponse.MinutesByDay day : minutesByDay) {
            if (day.minutes() > best.minutes()) {
                best = day;
            }
        }
        if (best.minutes() <= 0) {
            return null;
        }
        return best.date().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US);
    }

    private String findTopCategory(UUID userId, Instant from, Instant to) {
        List<Object[]> rows = timeEntryRepository.findTopCategoriesByMinutesInWindow(userId, from, to);
        if (rows.isEmpty()) {
            return null;
        }
        Object[] top = rows.get(0);
        String name = (String) top[0];
        int minutes = ((Number) top[1]).intValue();
        if (minutes <= 0) {
            return null;
        }
        return name;
    }

    private List<InsightsSummaryResponse.MinutesByDay> buildMinutesByDay(
            UUID userId, Instant from, Instant to, ZoneId zone) {
        List<Object[]> rows = timeEntryRepository.findCreatedAtAndDurationInWindow(userId, from, to);
        Map<LocalDate, Integer> byDay = new LinkedHashMap<>();
        for (Object[] row : rows) {
            Instant createdAt = (Instant) row[0];
            int minutes = ((Number) row[1]).intValue();
            LocalDate day = LocalDate.ofInstant(createdAt, zone);
            byDay.merge(day, minutes, Integer::sum);
        }
        List<InsightsSummaryResponse.MinutesByDay> result = new ArrayList<>(byDay.size());
        byDay.forEach((date, minutes) -> result.add(new InsightsSummaryResponse.MinutesByDay(date, minutes)));
        return result;
    }

    private List<InsightsSummaryResponse.TaskMinutes> buildTopTasks(
            UUID userId, Instant from, Instant to) {
        List<Object[]> rows = timeEntryRepository.findTopTasksByMinutesInWindow(
                userId, from, to, PageRequest.of(0, TOP_TASKS_LIMIT));
        List<InsightsSummaryResponse.TaskMinutes> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            UUID taskId = (UUID) row[0];
            String title = (String) row[1];
            int minutes = ((Number) row[2]).intValue();
            result.add(new InsightsSummaryResponse.TaskMinutes(taskId, title, minutes));
        }
        return result;
    }

    private void validateWindow(Instant from, Instant to) {
        if (from == null || to == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "from and to are required");
        }
        if (!to.isAfter(from)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "to must be after from");
        }
        long days = ChronoUnit.DAYS.between(from, to);
        if (days > MAX_WINDOW_DAYS) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "insights window cannot exceed 366 days");
        }
    }

    private static int toInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) value;
    }
}
