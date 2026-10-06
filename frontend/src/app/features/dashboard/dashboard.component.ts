import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { forkJoin } from 'rxjs';
import { canvasDeadlineTask } from '../../core/api/canvas-deadline';
import { ApiService } from '../../core/api/api.service';
import { CalendarEventDto, TaskDto } from '../../core/api/api.models';
import { AuthService } from '../../core/auth/auth.service';
import { OnboardingComponent } from '../onboarding/onboarding.component';
import { OnboardingService } from '../onboarding/onboarding.service';

interface DashStat { label: string; value: number; view: string; icon: string; tone: 'teal' | 'danger' | 'blue'; }
interface PlanItem { id: string; kind: 'event' | 'task' | 'canvas'; time: string; endTime?: string; title: string; subtitle?: string; sortKey: number; route: string; badge: string; }
interface DeadlineItem { id: string; title: string; subtitle?: string; dateLabel: string; timeLabel: string; badge: string; sortKey: number; route: string; overdue: boolean; }
interface NextItem { id: string; title: string; subtitle: string; badge: string; time: string; sortKey: number; urgency: number; priority: number; task?: TaskDto; canvas?: CalendarEventDto; }
interface WeekDay { key: string; weekday: string; date: string; count: number; today: boolean; }

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [OnboardingComponent, RouterLink, MatButtonModule, MatIconModule],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class DashboardComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  readonly guide = inject(OnboardingService);
  private readonly auth = inject(AuthService);

  readonly loading = signal(true);
  readonly completingId = signal<string | null>(null);
  readonly nextIndex = signal(0);
  readonly canvasConnected = signal<boolean | null>(null);
  readonly error = signal<string | null>(null);
  readonly actionError = signal<string | null>(null);
  readonly tasks = signal<TaskDto[]>([]);
  readonly events = signal<CalendarEventDto[]>([]);

  readonly planningTasks = computed(() => [
    ...this.tasks(),
    ...this.events().filter((event) => event.canvasKind === 'DEADLINE').map(canvasDeadlineTask),
  ]);

  readonly aiPrompts = ["What's due today?", 'Help me plan my study time', 'Show my hardest tasks'];

  readonly greeting = computed(() => {
    const hour = new Date().getHours();
    if (hour < 12) return 'Good morning';
    if (hour < 17) return 'Good afternoon';
    return 'Good evening';
  });
  readonly userName = computed(() => this.auth.currentUser()?.firstName ?? 'there');
  readonly dateLabel = computed(() => new Date().toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' }));

  readonly stats = computed((): DashStat[] => {
    const today = toDateKey(new Date());
    const weekStart = toDateKey(startOfWeek(new Date()));
    const weekEnd = toDateKey(addDays(startOfWeek(new Date()), 7));
    let dueToday = 0; let overdue = 0; let dueThisWeek = 0;
    for (const task of this.planningTasks()) {
      if (!isOpen(task) || !task.dueDate) continue;
      if (task.dueDate === today) dueToday++;
      if (isOverdue(task, new Date())) overdue++;
      if (task.dueDate >= weekStart && task.dueDate < weekEnd) dueThisWeek++;
    }
    return [
      { label: 'Due today', value: dueToday, view: 'today', icon: 'assignment', tone: 'teal' },
      { label: 'Overdue', value: overdue, view: 'overdue', icon: 'error_outline', tone: 'danger' },
      { label: 'This week', value: dueThisWeek, view: 'week', icon: 'calendar_month', tone: 'blue' },
    ];
  });

  readonly weeklyProgress = computed(() => {
    const weekStart = toDateKey(startOfWeek(new Date()));
    const weekEnd = toDateKey(addDays(startOfWeek(new Date()), 7));
    const week = this.planningTasks().filter((task) => task.status !== 'CANCELLED' && !!task.dueDate && task.dueDate >= weekStart && task.dueDate < weekEnd);
    const done = week.filter((task) => task.status === 'COMPLETED').length;
    return { done, total: week.length, percent: week.length ? Math.round((done / week.length) * 100) : 0 };
  });

  readonly weekDays = computed((): WeekDay[] => {
    const today = toDateKey(new Date());
    const start = startOfWeek(new Date());
    return Array.from({ length: 7 }, (_, index) => {
      const day = addDays(start, index); const key = toDateKey(day);
      return {
        key, weekday: day.toLocaleDateString(undefined, { weekday: 'short' }),
        date: day.toLocaleDateString(undefined, { month: 'short', day: 'numeric' }),
        count: this.planningTasks().filter((task) => isOpen(task) && task.dueDate === key).length,
        today: key === today,
      };
    });
  });

  readonly todayPlan = computed((): PlanItem[] => {
    const today = toDateKey(new Date()); const items: PlanItem[] = [];
    for (const event of this.events()) {
      const canvasTask = event.canvasKind === 'DEADLINE' ? canvasDeadlineTask(event) : null;
      if (canvasTask ? event.canvasCompleted || canvasTask.dueDate !== today : !overlapsDay(event.startAt, event.endAt, today)) continue;
      const start = new Date(event.startAt); const end = new Date(event.endAt); const canvas = event.canvasKind === 'DEADLINE';
      const canvasDue = canvasTask ? taskDateTime(canvasTask.dueDate!, canvasTask.dueTime ?? '23:59') : null;
      items.push({
        id: `event-${event.id}`, kind: canvas ? 'canvas' : 'event',
        time: canvas ? (canvasTask?.dueTime && canvasDue ? formatTime(canvasDue) : 'Time not provided') : event.allDay ? 'All day' : formatTime(start),
        endTime: event.allDay || canvas ? undefined : formatTime(end), title: event.title,
        subtitle: canvas ? 'Canvas assignment' : event.description ?? 'Calendar event',
        sortKey: canvasDue?.getTime() ?? (event.allDay ? 0 : start.getTime()), route: '/calendar', badge: canvas ? 'Due today' : 'Today',
      });
    }
    for (const task of this.tasks()) {
      if (!isOpen(task) || !task.dueDate || dueDateKey(task.dueDate) !== today) continue;
      const due = taskDateTime(task.dueDate, task.dueTime ?? '23:59'); if (!due) continue;
      items.push({
        id: `task-${task.id}`, kind: 'task', time: task.dueTime ? formatTime(due) : 'Any time',
        title: task.title, subtitle: task.description ?? priorityLabel(task), sortKey: due.getTime(), route: '/tasks',
        badge: isOverdue(task, new Date()) ? 'Overdue' : 'Due today',
      });
    }
    return items.sort((a, b) => a.sortKey - b.sortKey);
  });

  readonly upcomingDeadlines = computed((): DeadlineItem[] => {
    const now = new Date(); const horizon = addDays(startOfDay(now), 7); const items: DeadlineItem[] = [];
    const canvasIds = new Set(this.events().filter((event) => event.canvasKind === 'DEADLINE').map((event) => event.id));
    for (const task of this.planningTasks()) {
      if (!isOpen(task) || !task.dueDate || task.dueDate >= toDateKey(horizon)) continue;
      const due = taskDateTime(task.dueDate, task.dueTime ?? '23:59'); if (!due) continue;
      const overdue = isOverdue(task, now);
      items.push({
        id: `${canvasIds.has(task.id) ? 'event' : 'task'}-${task.id}`, title: task.title,
        subtitle: canvasIds.has(task.id) ? 'Canvas assignment' : task.description ?? priorityLabel(task),
        dateLabel: overdue ? 'Overdue' : relativeDueLabel(task.dueDate),
        timeLabel: task.dueTime ? formatTime(due) : canvasIds.has(task.id) ? 'Time not provided' : 'Any time',
        badge: overdue ? 'Needs attention' : relativeBadge(task.dueDate), sortKey: due.getTime(),
        route: canvasIds.has(task.id) ? '/calendar' : '/tasks', overdue,
      });
    }
    for (const event of this.events()) {
      if (event.canvasKind === 'DEADLINE') continue;
      const start = new Date(event.startAt); const end = new Date(event.endAt);
      if (end <= now || start >= horizon) continue;
      items.push({
        id: `event-${event.id}`, title: event.title, subtitle: event.description ?? 'Calendar event',
        dateLabel: relativeDueLabel(toDateKey(start)), timeLabel: event.allDay ? 'All day' : formatTime(start),
        badge: 'Event', sortKey: start.getTime(), route: '/calendar', overdue: false,
      });
    }
    return items.sort((a, b) => a.sortKey - b.sortKey);
  });

  readonly nextItems = computed((): NextItem[] => {
    const now = new Date();
    const canvasById = new Map(this.events().filter((event) => event.canvasKind === 'DEADLINE').map((event) => [event.id, event]));
    return this.planningTasks().filter((task) => isOpen(task) && !!task.dueDate).map((task) => {
      const due = taskDateTime(task.dueDate!, task.dueTime ?? '23:59')!;
      const overdue = isOverdue(task, now); const canvas = canvasById.get(task.id);
      return {
        id: task.id, title: task.title,
        subtitle: canvas ? 'Canvas assignment · completion is tracked in Prioritize only' : task.description ?? priorityLabel(task),
        badge: overdue ? 'Overdue' : relativeBadge(task.dueDate!),
        time: task.dueTime ? formatTime(due) : canvas ? 'Time not provided' : 'Any time',
        sortKey: due.getTime(), urgency: overdue ? 0 : task.dueDate === toDateKey(now) ? 1 : 2,
        priority: priorityRank(task), task: canvas ? undefined : task, canvas,
      };
    }).sort((a, b) => a.urgency - b.urgency || a.priority - b.priority || a.sortKey - b.sortKey).slice(0, 5);
  });
  readonly currentNext = computed(() => {
    const items = this.nextItems(); return items.length ? items[Math.min(this.nextIndex(), items.length - 1)] : null;
  });

  ngOnInit(): void {
    this.api.getCanvasFeed().subscribe({ next: (status) => this.canvasConnected.set(status.connected), error: () => this.canvasConnected.set(false) });
    this.reload();
  }
  reload(): void {
    this.loading.set(true); this.error.set(null); this.actionError.set(null);
    forkJoin({ tasks: this.api.listTasks(), events: this.api.listCalendarEvents() }).subscribe({
      next: ({ tasks, events }) => { this.tasks.set(tasks); this.events.set(events); this.nextIndex.set(0); this.loading.set(false); },
      error: (err) => {
        const status = err?.status as number | undefined;
        this.error.set(status === 401 ? 'Your session expired. Redirecting to login…' : status === 0 ? 'Cannot reach the API. Is the backend running on port 8080?' : 'Could not load dashboard.');
        this.loading.set(false);
      },
    });
  }
  moveNext(direction: number): void {
    const length = this.nextItems().length; if (length) this.nextIndex.set((this.nextIndex() + direction + length) % length);
  }
  completeNext(): void {
    const item = this.currentNext(); if (!item || this.completingId()) return;
    this.completingId.set(item.id); this.actionError.set(null);
    if (item.canvas) {
      this.api.setCanvasAssignmentCompleted(item.canvas.id, true).subscribe({
        next: () => { this.events.update((events) => events.map((event) => event.id === item.canvas!.id ? { ...event, canvasCompleted: true } : event)); this.finishCompletion(); },
        error: () => this.failCompletion(),
      });
      return;
    }
    const task = item.task!;
    this.api.updateTask(task.id, {
      title: task.title, description: task.description, categoryId: task.categoryId, projectId: task.projectId,
      dueDate: task.dueDate, dueTime: task.dueTime, estimatedMinutes: task.estimatedMinutes, priority: task.priority, status: 'COMPLETED',
    }).subscribe({
      next: (updated) => { this.tasks.update((tasks) => tasks.map((entry) => entry.id === updated.id ? updated : entry)); this.finishCompletion(); },
      error: () => this.failCompletion(),
    });
  }
  askAi(value: string): void {
    void this.router.navigate(['/assistant'], { queryParams: { q: value.trim() || 'What should I work on next?' } });
  }
  progressSegments(): boolean[] {
    const filled = Math.round((this.weeklyProgress().percent / 100) * 7);
    return Array.from({ length: 7 }, (_, index) => index < filled);
  }
  private finishCompletion(): void { this.completingId.set(null); this.nextIndex.set(0); }
  private failCompletion(): void { this.completingId.set(null); this.actionError.set('Could not mark this item complete. Please try again.'); }
}

function isOpen(task: TaskDto): boolean { return task.status === 'TODO' || task.status === 'IN_PROGRESS'; }
function priorityRank(task: TaskDto): number { return { URGENT: 0, HIGH: 1, MEDIUM: 2, LOW: 3 }[task.priority ?? 'MEDIUM']; }
function priorityLabel(task: TaskDto): string {
  const priority = task.priority ?? 'MEDIUM';
  return `${priority.charAt(0)}${priority.slice(1).toLowerCase()} priority`;
}
function startOfDay(date: Date): Date { return new Date(date.getFullYear(), date.getMonth(), date.getDate()); }
function startOfWeek(date: Date): Date { const d = startOfDay(date); d.setDate(d.getDate() - ((d.getDay() + 6) % 7)); return d; }
function addDays(date: Date, days: number): Date { const d = new Date(date); d.setDate(d.getDate() + days); return d; }
function toDateKey(date: Date): string { return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`; }
function dueDateKey(value: string): string { return /^\d{4}-\d{2}-\d{2}$/.test(value) ? value : toDateKey(new Date(value)); }
function taskDateTime(dateValue: string, timeValue: string): Date | null {
  const [y, m, d] = dueDateKey(dateValue).split('-').map(Number); const [hours, minutes] = timeValue.slice(0, 5).split(':').map(Number);
  return [y, m, d, hours, minutes].some(Number.isNaN) ? null : new Date(y, m - 1, d, hours, minutes);
}
function isOverdue(task: TaskDto, now: Date): boolean {
  if (!task.dueDate) return false; const today = toDateKey(now);
  return task.dueDate < today || (task.dueDate === today && !!task.dueTime && (taskDateTime(task.dueDate, task.dueTime)?.getTime() ?? Infinity) < now.getTime());
}
function overlapsDay(startAt: string, endAt: string, key: string): boolean {
  const [y, m, d] = key.split('-').map(Number); const start = new Date(y, m - 1, d).getTime(); const end = new Date(y, m - 1, d + 1).getTime();
  return new Date(startAt).getTime() < end && new Date(endAt).getTime() > start;
}
function formatTime(date: Date): string { return date.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' }); }
function relativeDueLabel(date: string): string {
  const today = toDateKey(new Date()); if (date === today) return 'Today';
  if (date === toDateKey(addDays(new Date(), 1))) return 'Tomorrow';
  return new Date(`${date}T12:00:00`).toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' });
}
function relativeBadge(date: string): string {
  const today = startOfDay(new Date()); const due = new Date(`${date}T12:00:00`);
  const days = Math.round((due.getTime() - new Date(today.getFullYear(), today.getMonth(), today.getDate(), 12).getTime()) / 86_400_000);
  return days <= 0 ? 'Due today' : days === 1 ? 'Due tomorrow' : `In ${days} days`;
}
