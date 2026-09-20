package com.prioritize.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.prioritize.exception.ResourceNotFoundException;
import com.prioritize.model.CalendarEvent;
import com.prioritize.model.Goal;
import com.prioritize.model.ReminderEntityType;
import com.prioritize.model.Routine;
import com.prioritize.model.ScheduleBlock;
import com.prioritize.model.Task;
import com.prioritize.model.User;
import com.prioritize.repository.CalendarEventRepository;
import com.prioritize.repository.GoalRepository;
import com.prioritize.repository.RoutineRepository;
import com.prioritize.repository.ScheduleBlockRepository;
import com.prioritize.repository.TaskRepository;
import com.prioritize.repository.UserRepository;

@Service
@Transactional(readOnly = true, noRollbackFor = {ResourceNotFoundException.class, IllegalArgumentException.class})
public class ReminderContentResolver {

    private static final DateTimeFormatter EVENT_FORMAT =
            DateTimeFormatter.ofPattern("MMM d · h:mm a");

    private final TaskRepository taskRepository;
    private final ScheduleBlockRepository scheduleBlockRepository;
    private final RoutineRepository routineRepository;
    private final CalendarEventRepository calendarEventRepository;
    private final GoalRepository goalRepository;
    private final UserRepository userRepository;

    public ReminderContentResolver(
            TaskRepository taskRepository,
            ScheduleBlockRepository scheduleBlockRepository,
            RoutineRepository routineRepository,
            CalendarEventRepository calendarEventRepository,
            GoalRepository goalRepository,
            UserRepository userRepository) {
        this.taskRepository = taskRepository;
        this.scheduleBlockRepository = scheduleBlockRepository;
        this.routineRepository = routineRepository;
        this.calendarEventRepository = calendarEventRepository;
        this.goalRepository = goalRepository;
        this.userRepository = userRepository;
    }

    public ReminderContent resolve(UUID userId, ReminderEntityType type, UUID entityId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        ZoneId zone = ZoneId.of(user.getTimezone() != null ? user.getTimezone() : "UTC");

        return switch (type) {
            case TASK -> resolveTask(userId, entityId, zone);
            case SCHEDULE_BLOCK -> resolveScheduleBlock(userId, entityId, zone);
            case ROUTINE -> resolveRoutine(userId, entityId, zone);
            case CALENDAR_EVENT -> resolveCalendarEvent(userId, entityId, zone);
            case GOAL -> resolveGoal(userId, entityId, zone);
        };
    }

    public Instant resolveEventAt(UUID userId, ReminderEntityType type, UUID entityId) {
        return resolve(userId, type, entityId).eventAt();
    }

    private ReminderContent resolveTask(UUID userId, UUID entityId, ZoneId zone) {
        Task task = taskRepository.findByIdAndUserId(entityId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("TASK not found"));
        if (task.getDueDate() == null) {
            throw new IllegalArgumentException("Task has no due date for reminders");
        }
        LocalTime time = task.getDueTime() != null ? task.getDueTime() : LocalTime.of(9, 0);
        Instant eventAt = task.getDueDate().atTime(time).atZone(zone).toInstant();
        return new ReminderContent(task.getTitle(), eventAt, formatEventAt(eventAt, zone));
    }

    private ReminderContent resolveScheduleBlock(UUID userId, UUID entityId, ZoneId zone) {
        ScheduleBlock block = scheduleBlockRepository.findByIdAndUserId(entityId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("SCHEDULE_BLOCK not found"));
        Instant eventAt = block.getStartAt();
        String title = taskRepository.findById(block.getTaskId())
                .map(Task::getTitle)
                .orElse("Scheduled block");
        return new ReminderContent(title, eventAt, formatEventAt(eventAt, zone));
    }

    private ReminderContent resolveRoutine(UUID userId, UUID entityId, ZoneId zone) {
        Routine routine = routineRepository.findByIdAndUserId(entityId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("ROUTINE not found"));
        Instant eventAt = routine.getStartTime().atDate(LocalDate.now(ZoneOffset.UTC)).atZone(zone).toInstant();
        return new ReminderContent(routine.getTitle(), eventAt, formatEventAt(eventAt, zone));
    }

    private ReminderContent resolveCalendarEvent(UUID userId, UUID entityId, ZoneId zone) {
        CalendarEvent event = calendarEventRepository.findByIdAndUserId(entityId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("CALENDAR_EVENT not found"));
        Instant eventAt = event.getStartAt();
        return new ReminderContent(event.getTitle(), eventAt, formatEventAt(eventAt, zone));
    }

    private ReminderContent resolveGoal(UUID userId, UUID entityId, ZoneId zone) {
        Goal goal = goalRepository.findByIdAndUserId(entityId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("GOAL not found"));
        if (goal.getTargetDate() == null) {
            throw new IllegalArgumentException("Goal has no target date for reminders");
        }
        Instant eventAt = goal.getTargetDate().atTime(9, 0).atZone(zone).toInstant();
        return new ReminderContent(goal.getTitle(), eventAt, formatEventAt(eventAt, zone));
    }

    private static String formatEventAt(Instant eventAt, ZoneId zone) {
        return EVENT_FORMAT.withZone(zone).format(eventAt);
    }

    public record ReminderContent(String title, Instant eventAt, String eventAtLabel) {
    }
}
