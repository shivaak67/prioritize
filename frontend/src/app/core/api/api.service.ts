import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  AssistantChatRequest,
  AssistantChatResponse,
  AssistantStatusResponse,
  CalendarEventDto,
  CanvasFeedStatus,
  CategoryDto,
  CreateCalendarEventRequest,
  CreateCategoryRequest,
  DashboardSummary,
  CreateGoalRequest,
  CreateProjectRequest,
  CreateReminderRequest,
  CreateRoutineRequest,
  CreateScheduleBlockRequest,
  CreateTaskRequest,
  CreateTimeEntryRequest,
  GoalDto,
  InsightsSummary,
  NotificationDto,
  NotificationSettingsDto,
  PhoneStatusDto,
  PhoneUpdateRequest,
  PhoneVerifyRequest,
  ProjectDto,
  ReminderDto,
  ReminderScheduleRequest,
  ReminderScheduleResponse,
  RoutineDto,
  RoutineOccurrenceDto,
  ScheduleBlockDto,
  TaskDto,
  TimeEntryDto,
  UpdateCalendarEventRequest,
  UpdateCategoryRequest,
  UpdateGoalRequest,
  UpdateNotificationSettingsRequest,
  UpdateProjectRequest,
  UpdateReminderRequest,
  UpdateRoutineRequest,
  UpdateScheduleBlockRequest,
  UpdateTaskRequest,
  UpdateTimeEntryRequest,
} from './api.models';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;
  getCanvasFeed(): Observable<CanvasFeedStatus> {
    return this.http.get<CanvasFeedStatus>(`${this.base}/api/integrations/canvas`);
  }
  setCanvasDeadlineTime(id: string, time: string, timezone: string): Observable<CalendarEventDto> {
    return this.http.put<CalendarEventDto>(`${this.base}/api/integrations/canvas/assignments/${id}/deadline-time`, { time, timezone });
  }

  setCanvasAssignmentCompleted(id: string, completed: boolean): Observable<void> {
    return this.http.put<void>(`${this.base}/api/integrations/canvas/assignments/${id}/completion`, { completed });
  }
  connectCanvasFeed(feedUrl: string, timezone: string): Observable<CanvasFeedStatus> {
    return this.http.put<CanvasFeedStatus>(`${this.base}/api/integrations/canvas`, { feedUrl, timezone });
  }
  syncCanvasFeed(): Observable<CanvasFeedStatus> {
    return this.http.post<CanvasFeedStatus>(`${this.base}/api/integrations/canvas/sync`, {});
  }
  disconnectCanvasFeed(): Observable<void> {
    return this.http.delete<void>(`${this.base}/api/integrations/canvas`);
  }

  // --- Categories ---

  listCategories(): Observable<CategoryDto[]> {
    return this.http.get<CategoryDto[]>(`${this.base}/api/categories`);
  }

  createCategory(body: CreateCategoryRequest): Observable<CategoryDto> {
    return this.http.post<CategoryDto>(`${this.base}/api/categories`, body);
  }

  updateCategory(id: string, body: UpdateCategoryRequest): Observable<CategoryDto> {
    return this.http.put<CategoryDto>(`${this.base}/api/categories/${id}`, body);
  }

  deleteCategory(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/api/categories/${id}`);
  }

  // --- Goals ---

  listGoals(): Observable<GoalDto[]> {
    return this.http.get<GoalDto[]>(`${this.base}/api/goals`);
  }

  createGoal(body: CreateGoalRequest): Observable<GoalDto> {
    return this.http.post<GoalDto>(`${this.base}/api/goals`, body);
  }

  updateGoal(id: string, body: UpdateGoalRequest): Observable<GoalDto> {
    return this.http.put<GoalDto>(`${this.base}/api/goals/${id}`, body);
  }

  deleteGoal(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/api/goals/${id}`);
  }

  // --- Projects ---

  listProjects(): Observable<ProjectDto[]> {
    return this.http.get<ProjectDto[]>(`${this.base}/api/projects`);
  }

  createProject(body: CreateProjectRequest): Observable<ProjectDto> {
    return this.http.post<ProjectDto>(`${this.base}/api/projects`, body);
  }

  updateProject(id: string, body: UpdateProjectRequest): Observable<ProjectDto> {
    return this.http.put<ProjectDto>(`${this.base}/api/projects/${id}`, body);
  }

  deleteProject(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/api/projects/${id}`);
  }

  // --- Tasks ---

  listTasks(): Observable<TaskDto[]> {
    return this.http.get<TaskDto[]>(`${this.base}/api/tasks`);
  }

  createTask(body: CreateTaskRequest): Observable<TaskDto> {
    return this.http.post<TaskDto>(`${this.base}/api/tasks`, body);
  }

  updateTask(id: string, body: UpdateTaskRequest): Observable<TaskDto> {
    return this.http.put<TaskDto>(`${this.base}/api/tasks/${id}`, body);
  }

  deleteTask(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/api/tasks/${id}`);
  }

  // --- Schedule blocks ---

  listScheduleBlocks(from: string, to: string): Observable<ScheduleBlockDto[]> {
    return this.http.get<ScheduleBlockDto[]>(`${this.base}/api/schedule-blocks`, {
      params: { from, to },
    });
  }

  createScheduleBlock(body: CreateScheduleBlockRequest): Observable<ScheduleBlockDto> {
    return this.http.post<ScheduleBlockDto>(`${this.base}/api/schedule-blocks`, body);
  }

  updateScheduleBlock(id: string, body: UpdateScheduleBlockRequest): Observable<ScheduleBlockDto> {
    return this.http.put<ScheduleBlockDto>(`${this.base}/api/schedule-blocks/${id}`, body);
  }

  deleteScheduleBlock(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/api/schedule-blocks/${id}`);
  }

  // --- Calendar events ---

  listCalendarEvents(from?: string, to?: string): Observable<CalendarEventDto[]> {
    return this.http.get<CalendarEventDto[]>(`${this.base}/api/calendar-events`, {
      params: from && to ? { from, to } : {},
    });
  }

  createCalendarEvent(body: CreateCalendarEventRequest): Observable<CalendarEventDto> {
    return this.http.post<CalendarEventDto>(`${this.base}/api/calendar-events`, body);
  }

  updateCalendarEvent(id: string, body: UpdateCalendarEventRequest): Observable<CalendarEventDto> {
    return this.http.put<CalendarEventDto>(`${this.base}/api/calendar-events/${id}`, body);
  }

  deleteCalendarEvent(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/api/calendar-events/${id}`);
  }

  // --- Routines ---

  listRoutines(): Observable<RoutineDto[]> {
    return this.http.get<RoutineDto[]>(`${this.base}/api/routines`);
  }

  createRoutine(body: CreateRoutineRequest): Observable<RoutineDto> {
    return this.http.post<RoutineDto>(`${this.base}/api/routines`, body);
  }

  updateRoutine(id: string, body: UpdateRoutineRequest): Observable<RoutineDto> {
    return this.http.put<RoutineDto>(`${this.base}/api/routines/${id}`, body);
  }

  deleteRoutine(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/api/routines/${id}`);
  }

  listRoutineOccurrences(from: string, to: string): Observable<RoutineOccurrenceDto[]> {
    return this.http.get<RoutineOccurrenceDto[]>(`${this.base}/api/routines/occurrences`, {
      params: { from, to },
    });
  }

  // --- Reminders ---

  listReminders(): Observable<ReminderDto[]> {
    return this.http.get<ReminderDto[]>(`${this.base}/api/reminders`);
  }

  clearReminderHistory(): Observable<void> {
    return this.http.delete<void>(`${this.base}/api/reminders/history`);
  }

  createReminder(body: CreateReminderRequest): Observable<ReminderDto> {
    return this.http.post<ReminderDto>(`${this.base}/api/reminders`, body);
  }

  updateReminder(id: string, body: UpdateReminderRequest): Observable<ReminderDto> {
    return this.http.put<ReminderDto>(`${this.base}/api/reminders/${id}`, body);
  }

  deleteReminder(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/api/reminders/${id}`);
  }

  cancelReminder(id: string): Observable<ReminderDto> {
    return this.http.post<ReminderDto>(`${this.base}/api/reminders/${id}/cancel`, {});
  }

  scheduleReminders(body: ReminderScheduleRequest): Observable<ReminderScheduleResponse> {
    return this.http.post<ReminderScheduleResponse>(`${this.base}/api/reminders/schedule`, body);
  }

  // --- Phone verification ---

  getPhoneStatus(): Observable<PhoneStatusDto> {
    return this.http.get<PhoneStatusDto>(`${this.base}/api/phone`);
  }

  updatePhone(body: PhoneUpdateRequest): Observable<PhoneStatusDto> {
    return this.http.put<PhoneStatusDto>(`${this.base}/api/phone`, body);
  }

  sendPhoneCode(): Observable<PhoneStatusDto> {
    return this.http.post<PhoneStatusDto>(`${this.base}/api/phone/send-code`, {});
  }

  verifyPhone(body: PhoneVerifyRequest): Observable<PhoneStatusDto> {
    return this.http.post<PhoneStatusDto>(`${this.base}/api/phone/verify`, body);
  }

  // --- Notifications ---

  listNotifications(unreadOnly = false): Observable<NotificationDto[]> {
    return this.http.get<NotificationDto[]>(`${this.base}/api/notifications`, {
      params: unreadOnly ? { unreadOnly: 'true' } : {},
    });
  }

  markNotificationRead(id: string): Observable<NotificationDto> {
    return this.http.post<NotificationDto>(`${this.base}/api/notifications/${id}/read`, {});
  }

  deleteNotification(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/api/notifications/${id}`);
  }

  // --- Notification settings ---

  getNotificationSettings(): Observable<NotificationSettingsDto> {
    return this.http.get<NotificationSettingsDto>(`${this.base}/api/notification-settings`);
  }

  updateNotificationSettings(
    body: UpdateNotificationSettingsRequest,
  ): Observable<NotificationSettingsDto> {
    return this.http.put<NotificationSettingsDto>(
      `${this.base}/api/notification-settings`,
      body,
    );
  }

  // --- Time entries ---

  listTimeEntries(taskId?: string): Observable<TimeEntryDto[]> {
    return this.http.get<TimeEntryDto[]>(`${this.base}/api/time-entries`, {
      params: taskId ? { taskId } : {},
    });
  }

  createTimeEntry(body: CreateTimeEntryRequest): Observable<TimeEntryDto> {
    return this.http.post<TimeEntryDto>(`${this.base}/api/time-entries`, body);
  }

  updateTimeEntry(id: string, body: UpdateTimeEntryRequest): Observable<TimeEntryDto> {
    return this.http.put<TimeEntryDto>(`${this.base}/api/time-entries/${id}`, body);
  }

  deleteTimeEntry(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/api/time-entries/${id}`);
  }

  // --- Dashboard ---

  getDashboardSummary(): Observable<DashboardSummary> {
    return this.http.get<DashboardSummary>(`${this.base}/api/dashboard/summary`, { params: { timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone } });
  }

  // --- AI assistant ---

  getAssistantStatus(): Observable<AssistantStatusResponse> {
    return this.http.get<AssistantStatusResponse>(`${this.base}/api/assistant/status`);
  }

  chatWithAssistant(body: AssistantChatRequest): Observable<AssistantChatResponse> {
    return this.http.post<AssistantChatResponse>(`${this.base}/api/assistant/chat`, body);
  }

  // --- Insights ---

  getInsightsSummary(from: string, to: string): Observable<InsightsSummary> {
    return this.http.get<InsightsSummary>(`${this.base}/api/insights/summary`, {
      params: { from, to },
    });
  }
}
