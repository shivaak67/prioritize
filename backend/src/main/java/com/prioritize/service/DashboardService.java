package com.prioritize.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import com.prioritize.repository.CalendarEventRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.prioritize.dto.DashboardSummaryResponse;
import com.prioritize.model.Project;
import com.prioritize.model.Task;
import com.prioritize.model.TaskPriority;
import com.prioritize.model.TaskStatus;
import com.prioritize.repository.ProjectRepository;
import com.prioritize.repository.TaskRepository;

@Service
public class DashboardService {

    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final Clock clock;
    private final CalendarEventRepository calendarRepository;

    public DashboardService(
            TaskRepository taskRepository,
            ProjectRepository projectRepository,
            CalendarEventRepository calendarRepository,
            Clock clock) {
        this.taskRepository = taskRepository;
        this.projectRepository = projectRepository;
        this.clock = clock;
        this.calendarRepository = calendarRepository;
    }

    @Transactional(readOnly = true)
    public DashboardSummaryResponse summary(UUID userId) {
        return summary(userId, ZoneOffset.UTC);
    }

    @Transactional(readOnly = true)
    public DashboardSummaryResponse summary(UUID userId, ZoneId zone) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate endOfWeekExclusive = weekStart.plusDays(7);

        List<Task> tasks = taskRepository.findFiltered(userId, null, null, null);
        Map<UUID, String> projectNames = projectRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .collect(Collectors.toMap(Project::getId, Project::getTitle, (a, b) -> a, LinkedHashMap::new));

        int dueToday = 0;
        int dueThisWeek = 0;
        int overdue = 0;
        int highPriority = 0;
        int completed = 0;
        int remaining = 0;
        double hoursThisWeek = 0.0;

        Map<UUID, DashboardSummaryResponse.WorkloadByProject> workload = new LinkedHashMap<>();

        for (Task task : tasks) {
            if (task.getStatus() == TaskStatus.COMPLETED) {
                completed++;
            }

            boolean open = task.getStatus() == TaskStatus.TODO || task.getStatus() == TaskStatus.IN_PROGRESS;
            if (!open) {
                continue;
            }

            remaining++;

            if (task.getPriority() == TaskPriority.HIGH || task.getPriority() == TaskPriority.URGENT) {
                highPriority++;
            }

            LocalDate due = task.getDueDate();
            if (due != null) {
                if (due.equals(today)) {
                    dueToday++;
                }
                if (due.isBefore(today) || (due.equals(today) && task.getDueTime() != null
                        && due.atTime(task.getDueTime()).atZone(zone).toInstant().isBefore(clock.instant()))) {
                    overdue++;
                }
                if (!due.isBefore(weekStart) && due.isBefore(endOfWeekExclusive)) {
                    dueThisWeek++;
                    hoursThisWeek += minutesToHours(task.getEstimatedMinutes());
                }
            }

            UUID projectId = task.getProjectId();
            if (projectId == null) {
                continue;
            }

            String projectName = projectNames.getOrDefault(projectId, "Unknown project");
            DashboardSummaryResponse.WorkloadByProject existing = workload.get(projectId);
            if (existing == null) {
                workload.put(
                        projectId,
                        new DashboardSummaryResponse.WorkloadByProject(
                                projectId,
                                projectName,
                                1,
                                minutesToHours(task.getEstimatedMinutes())));
            } else {
                workload.put(
                        projectId,
                        new DashboardSummaryResponse.WorkloadByProject(
                                existing.projectId(),
                                existing.projectName(),
                                existing.taskCount() + 1,
                                existing.estimatedHours() + minutesToHours(task.getEstimatedMinutes())));
            }
        }

        for (var event : calendarRepository.findByUserIdAndCanvasKeyIsNotNull(userId)) {
            if (!"DEADLINE".equals(event.getCanvasKind())) continue;
            if (event.isCanvasCompleted()) { completed++; continue; }
            remaining++;
            LocalDate due = event.isAllDay() && event.getCanvasStartDate() != null
                    ? event.getCanvasStartDate() : event.getStartAt().atZone(zone).toLocalDate();
            if (due.equals(today)) dueToday++;
            if (!due.isBefore(weekStart) && due.isBefore(endOfWeekExclusive)) dueThisWeek++;
            if (due.isBefore(today) || (!event.isAllDay() && event.getStartAt().isBefore(clock.instant()))) overdue++;
        }

        List<DashboardSummaryResponse.WorkloadByProject> workloadByProject = workload.values().stream()
                .sorted(Comparator.comparing(DashboardSummaryResponse.WorkloadByProject::estimatedHours).reversed())
                .map(w -> new DashboardSummaryResponse.WorkloadByProject(
                        w.projectId(),
                        w.projectName(),
                        w.taskCount(),
                        roundHours(w.estimatedHours())))
                .toList();

        return new DashboardSummaryResponse(
                dueToday,
                dueThisWeek,
                overdue,
                highPriority,
                completed,
                remaining,
                roundHours(hoursThisWeek),
                workloadByProject);
    }

    private static double minutesToHours(Integer minutes) {
        return minutes == null ? 0.0 : minutes / 60.0;
    }

    private static double roundHours(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
