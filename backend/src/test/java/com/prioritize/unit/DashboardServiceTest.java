package com.prioritize.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.prioritize.dto.DashboardSummaryResponse;
import com.prioritize.model.Project;
import com.prioritize.model.Task;
import com.prioritize.model.TaskPriority;
import com.prioritize.model.TaskStatus;
import com.prioritize.repository.ProjectRepository;
import com.prioritize.repository.TaskRepository;
import com.prioritize.service.DashboardService;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private com.prioritize.repository.CalendarEventRepository calendarRepository;
    private DashboardService dashboardService;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-10T15:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        dashboardService = new DashboardService(taskRepository, projectRepository, calendarRepository, clock);
    }

    @Test
    void summaryAggregatesOpenTasks() {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        Project project = new Project();
        project.setId(projectId);
        project.setTitle("Algorithms");

        Task dueToday = openTask(projectId, "HW1", LocalDate.parse("2026-09-10"), 180, TaskPriority.URGENT);
        Task overdue = openTask(projectId, "HW0", LocalDate.parse("2026-09-08"), 120, TaskPriority.HIGH);
        Task later = openTask(projectId, "Project", LocalDate.parse("2026-09-20"), 480, TaskPriority.MEDIUM);
        Task done = openTask(projectId, "Done", LocalDate.parse("2026-09-10"), 60, TaskPriority.LOW);
        done.setStatus(TaskStatus.COMPLETED);

        when(taskRepository.findFiltered(eq(userId), eq(null), eq(null), eq(null)))
                .thenReturn(List.of(dueToday, overdue, later, done));
        when(projectRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(project));

        DashboardSummaryResponse summary = dashboardService.summary(userId);

        assertThat(summary.dueTodayCount()).isEqualTo(1);
        assertThat(summary.dueThisWeekCount()).isEqualTo(2);
        assertThat(summary.overdueCount()).isEqualTo(1);
        assertThat(summary.highPriorityCount()).isEqualTo(2);
        assertThat(summary.completedCount()).isEqualTo(1);
        assertThat(summary.remainingCount()).isEqualTo(3);
        assertThat(summary.estimatedHoursRemainingThisWeek()).isEqualTo(5.0);
        assertThat(summary.workloadByProject()).hasSize(1);
        assertThat(summary.workloadByProject().getFirst().taskCount()).isEqualTo(3);
        assertThat(summary.workloadByProject().getFirst().estimatedHours()).isEqualTo(13.0);
        assertThat(summary.workloadByProject().getFirst().projectName()).isEqualTo("Algorithms");
    }

    private static Task openTask(
            UUID projectId, String title, LocalDate due, int estimatedMinutes, TaskPriority priority) {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setProjectId(projectId);
        task.setTitle(title);
        task.setDueDate(due);
        task.setEstimatedMinutes(estimatedMinutes);
        task.setPriority(priority);
        task.setStatus(TaskStatus.TODO);
        return task;
    }

    @Test
    void canvasCountsUseLocalDatesAndCompletionWithoutCountingEvents() {
        UUID userId = UUID.randomUUID();
        var quiz = new com.prioritize.model.CalendarEvent();
        quiz.setCanvasKind("DEADLINE"); quiz.setAllDay(true);
        quiz.setCanvasStartDate(LocalDate.parse("2026-09-10"));
        quiz.setStartAt(Instant.parse("2026-09-11T00:00:00Z"));
        var timed = new com.prioritize.model.CalendarEvent();
        timed.setCanvasKind("DEADLINE"); timed.setStartAt(Instant.parse("2026-09-10T14:00:00Z"));
        var meeting = new com.prioritize.model.CalendarEvent(); meeting.setCanvasKind("EVENT");
        when(calendarRepository.findByUserIdAndCanvasKeyIsNotNull(userId)).thenReturn(List.of(quiz, timed, meeting));
        var result = dashboardService.summary(userId, java.time.ZoneId.of("America/Chicago"));
        assertThat(result.dueTodayCount()).isEqualTo(2);
        assertThat(result.overdueCount()).isEqualTo(1);
        assertThat(result.remainingCount()).isEqualTo(2);
        quiz.setCanvasCompleted(true);
        result = dashboardService.summary(userId, java.time.ZoneId.of("America/Chicago"));
        assertThat(result.dueTodayCount()).isEqualTo(1);
        assertThat(result.completedCount()).isEqualTo(1);
    }
}
