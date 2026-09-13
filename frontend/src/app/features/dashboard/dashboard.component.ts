import { canvasDeadlineTask } from '../../core/api/canvas-deadline';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { OnboardingComponent } from '../onboarding/onboarding.component';
import { OnboardingService } from '../onboarding/onboarding.service';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { forkJoin } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import {
  CalendarEventDto,
  TaskDto,
} from '../../core/api/api.models';

interface DashStat {
  label: string;
  value: number;
  view: string;
}

interface PlanItem {
  id: string;
  kind: 'event' | 'task';
  time: string;
  endTime?: string;
  title: string;
  subtitle?: string;
  sortKey: number;
}

interface DeadlineItem {
  id: string;
  title: string;
  when: string;
  sortKey: number;
  route: string;
  overdue: boolean;
}

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [OnboardingComponent, RouterLink, MatButtonModule, MatIconModule],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class DashboardComponent implements OnInit {
  private readonly api = inject(ApiService);
  readonly guide = inject(OnboardingService);
  private readonly auth = inject(AuthService);

  readonly loading = signal(true);
  readonly canvasConnected = signal<boolean | null>(null);
  readonly error = signal<string | null>(null);
  readonly tasks = signal<TaskDto[]>([]);
  readonly events = signal<CalendarEventDto[]>([]);

  readonly planningTasks = computed(() => [...this.tasks(),
    ...this.events().filter(e => e.canvasKind === 'DEADLINE').map(canvasDeadlineTask)]);

  readonly aiPrompts = [
    'What should I work on today?',
    'What tasks are overdue?',
    "What's on my calendar this week?",
  ];

  readonly greeting = computed(() => {
    const hour = new Date().getHours();
    if (hour < 12) {
      return 'Good morning';
    }
    if (hour < 17) {
      return 'Good afternoon';
    }
    return 'Good evening';
  });

  readonly userName = computed(() => this.auth.currentUser()?.firstName ?? 'there');

  readonly dateLabel = computed(() =>
    new Date().toLocaleDateString(undefined, {
      weekday: 'long',
      month: 'long',
      day: 'numeric',
    }),
  );

  readonly stats = computed((): DashStat[] => {
    const today = toDateKey(new Date());
    const weekEnd = addDays(startOfWeek(new Date()), 7);
    const weekStartKey = toDateKey(startOfWeek(new Date()));
    const weekEndKey = toDateKey(weekEnd);

    let dueToday = 0;
    let overdue = 0;
    let dueThisWeek = 0;

    for (const task of this.planningTasks()) {
      const isOpen = task.status === 'TODO' || task.status === 'IN_PROGRESS';
      if (!isOpen || !task.dueDate) {
        continue;
      }
      if (task.dueDate === today) {
        dueToday += 1;
      }
      if (task.dueDate < today || (task.dueDate === today && task.dueTime && (taskDateTime(task.dueDate, task.dueTime)?.getTime() ?? Infinity) < Date.now())) {
        overdue += 1;
      }
      if (task.dueDate >= weekStartKey && task.dueDate < weekEndKey) {
        dueThisWeek += 1;
      }
    }

    return [
      { label: 'Due Today', value: dueToday, view: 'today' },
      { label: 'Overdue', value: overdue, view: 'overdue' },
      { label: 'This Week', value: dueThisWeek, view: 'week' },
    ];
  });

  readonly todayPlan = computed((): PlanItem[] => {
    const todayKey = toDateKey(new Date());
    const items: PlanItem[] = [];

    for (const event of this.events()) {
      if (event.canvasKind === 'DEADLINE' ? event.canvasCompleted || canvasDeadlineTask(event).dueDate !== todayKey
          : !overlapsDay(event.startAt, event.endAt, todayKey)) {
        continue;
      }
      const start = new Date(event.startAt);
      const end = new Date(event.endAt);
      items.push({
        id: `event-${event.id}`,
        kind: 'event',
        time: (event.canvasKind === 'DEADLINE' ? 'Due ' : '') + (event.allDay ? 'All day' : formatTime(start)),
        endTime: event.allDay || event.canvasKind === 'DEADLINE' ? undefined : formatTime(end),
        title: event.title,
        subtitle: event.canvasKind === 'DEADLINE' ? 'Canvas assignment' : event.description ?? undefined,
        sortKey: event.allDay ? 0 : start.getTime(),
      });
    }

    for (const task of this.tasks()) {
      if (!task.dueDate || dueDateKey(task.dueDate) !== todayKey) {
        continue;
      }
      if (task.status === 'COMPLETED' || task.status === 'CANCELLED') {
        continue;
      }
      const start = taskDateTime(task.dueDate, task.dueTime ?? '23:59');
      if (!start) {
        continue;
      }
      items.push({
        id: `task-${task.id}`,
        kind: 'task',
        time: task.dueTime ? formatTime(start) : 'Any time',
        title: task.title,
        sortKey: start.getTime(),
      });
    }

    return items.sort((a, b) => a.sortKey - b.sortKey);
  });

  readonly weeklyProgress = computed(() => {
    const weekStart = startOfWeek(new Date());
    const weekEnd = addDays(weekStart, 7);
    const weekStartKey = toDateKey(weekStart);
    const weekEndKey = toDateKey(weekEnd);

    let total = 0;
    let done = 0;

    for (const task of this.planningTasks()) {
      if (task.status === 'CANCELLED' || !task.dueDate || task.dueDate < weekStartKey || task.dueDate >= weekEndKey) {
        continue;
      }
      total += 1;
      if (task.status === 'COMPLETED') {
        done += 1;
      }
    }

    const percent = total === 0 ? 0 : Math.round((done / total) * 100);
    return { done, total, percent };
  });

  readonly upcomingDeadlines = computed((): DeadlineItem[] => {
    const now = new Date();
    const todayKey = toDateKey(now);
    const horizon = addDays(startOfDay(now), 7);
    const horizonKey = toDateKey(horizon);
    const items: DeadlineItem[] = [];

    for (const task of this.planningTasks()) {
      const isOpen = task.status === 'TODO' || task.status === 'IN_PROGRESS';
      if (
        !isOpen ||
        !task.dueDate ||
        task.dueDate >= horizonKey
      ) {
        continue;
      }
      const due = taskDateTime(task.dueDate, task.dueTime ?? '23:59');
      if (!due) continue;
      const overdue = task.dueDate < todayKey || (!!task.dueTime && due.getTime() < now.getTime());
      items.push({
        id: `${this.events().some(e => e.id === task.id && e.canvasKind === 'DEADLINE') ? 'event' : 'task'}-${task.id}`,
        title: task.title,
        when: `${overdue ? 'Overdue · ' : ''}${relativeDueLabel(task.dueDate)}${task.dueTime ? ` · ${formatTime(due)}` : ''}`,
        sortKey: due.getTime(),
        route: this.events().some(e => e.id === task.id && e.canvasKind === 'DEADLINE') ? '/calendar' : '/tasks',
        overdue,
      });
    }

    for (const event of this.events()) {
      if (event.canvasKind === 'DEADLINE') continue;
      const start = new Date(event.startAt);
      const end = new Date(event.endAt);
      const dayKey = toDateKey(start);
      if (end.getTime() <= now.getTime() || start.getTime() >= horizon.getTime()) {
        continue;
      }
      items.push({
        id: `event-${event.id}`,
        title: event.title,
        when: event.allDay
          ? relativeDueLabel(dayKey)
          : `${relativeDueLabel(dayKey)} · ${formatTimeRange(start, end)}`,
        sortKey: start.getTime(),
        route: '/calendar',
        overdue: false,
      });
    }

    return items.sort((a, b) => a.sortKey - b.sortKey);
  });

  ngOnInit(): void {
    this.api.getCanvasFeed().subscribe({
      next: status => this.canvasConnected.set(status.connected),
      error: () => this.canvasConnected.set(false),
    });
    this.reload();
  }

  reload(): void {
    this.loading.set(true);
    this.error.set(null);

    forkJoin({
      tasks: this.api.listTasks(),
      events: this.api.listCalendarEvents(),
    }).subscribe({
      next: ({ tasks, events }) => {
        this.tasks.set(tasks);
        this.events.set(events);
        this.loading.set(false);
      },
      error: (err) => {
        const statusCode = err?.status as number | undefined;
        if (statusCode === 401) {
          this.error.set('Your session expired. Redirecting to login…');
        } else if (statusCode === 0) {
          this.error.set('Cannot reach the API. Is the backend running on port 8080?');
        } else {
          this.error.set('Could not load dashboard.');
        }
        this.loading.set(false);
      },
    });
  }

  progressSegments(): boolean[] {
    const progress = this.weeklyProgress();
    const filled = Math.round((progress.percent / 100) * 10);
    return Array.from({ length: 10 }, (_, i) => i < filled);
  }
}

function startOfDay(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

function startOfWeek(date: Date): Date {
  const d = new Date(date.getFullYear(), date.getMonth(), date.getDate());
  const mondayOffset = (d.getDay() + 6) % 7;
  d.setDate(d.getDate() - mondayOffset);
  return d;
}

function addDays(date: Date, days: number): Date {
  const d = new Date(date);
  d.setDate(d.getDate() + days);
  return d;
}

function toDateKey(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

function dueDateKey(dueDate: string): string {
  if (/^\d{4}-\d{2}-\d{2}$/.test(dueDate)) {
    return dueDate;
  }
  return toDateKey(new Date(dueDate));
}

function overlapsDay(startAt: string, endAt: string, dayKey: string): boolean {
  const [y, m, d] = dayKey.split('-').map(Number);
  const dayStart = new Date(y, m - 1, d, 0, 0, 0, 0).getTime();
  const dayEnd = new Date(y, m - 1, d + 1, 0, 0, 0, 0).getTime();
  const start = new Date(startAt).getTime();
  const end = new Date(endAt).getTime();
  return start < dayEnd && end > dayStart;
}

function taskDateTime(dueDate: string, dueTime: string): Date | null {
  const datePart = dueDateKey(dueDate);
  const timePart = dueTime.length >= 5 ? dueTime.slice(0, 5) : dueTime;
  const [y, m, d] = datePart.split('-').map(Number);
  const [hh, mm] = timePart.split(':').map(Number);
  if ([y, m, d, hh, mm].some((n) => Number.isNaN(n))) {
    return null;
  }
  return new Date(y, m - 1, d, hh, mm, 0, 0);
}

function formatTime(date: Date): string {
  return date.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
}

function formatTimeRange(start: Date, end: Date): string {
  return `${formatTime(start)} – ${formatTime(end)}`;
}

function parseTime(value: string): number {
  const match = value.match(/(\d+):(\d+)/);
  if (!match) {
    return 0;
  }
  const hours = Number(match[1]);
  const minutes = Number(match[2]);
  const isPm = value.toLowerCase().includes('pm');
  const isAm = value.toLowerCase().includes('am');
  let h = hours;
  if (isPm && h < 12) {
    h += 12;
  }
  if (isAm && h === 12) {
    h = 0;
  }
  return h * 60 + minutes;
}

function relativeDueLabel(dueDate: string): string {
  const today = toDateKey(new Date());
  if (dueDate === today) {
    return 'Today';
  }
  const due = new Date(`${dueDate}T12:00:00`);
  const now = new Date();
  now.setHours(12, 0, 0, 0);
  const days = Math.round((due.getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
  if (days === 1) {
    return 'Tomorrow';
  }
  return due.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}
