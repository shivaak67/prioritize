import {
  Component,
  OnInit,
  OnDestroy,
  afterNextRender,
  Injector,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Observable, Subscription } from 'rxjs';
import { localInput, validLocal } from '../../shared/planning-time';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { forkJoin } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { CalendarEventDto, TaskDto } from '../../core/api/api.models';

export interface CalendarChip {
  id: string;
  label: string;
  entityId: string;
  kind: 'task' | 'event';
  canvasKind?: 'DEADLINE' | 'EVENT' | null;
}

export interface CalendarCell {
  key: string;
  day: number | null;
  inMonth: boolean;
  isToday: boolean;
  chips: CalendarChip[];
}

@Component({
  selector: 'app-calendar',
  standalone: true,
  imports: [FormsModule, MatButtonModule, MatIconModule, DatePipe, RouterLink],
  templateUrl: './calendar.component.html',
  styleUrl: './calendar.component.scss',
})
export class CalendarComponent implements OnInit, OnDestroy {
  private readonly api = inject(ApiService);
  private readonly injector = inject(Injector);
  private revealPanel(): void {
    afterNextRender(() => document.getElementById('day-title')?.focus(), {
      injector: this.injector,
    });
  }

  readonly weekdays = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  readonly tasks = signal<TaskDto[]>([]);
  readonly events = signal<CalendarEventDto[]>([]);
  readonly viewMonth = signal(startOfMonth(new Date()));

  readonly monthLabel = computed(() => {
    const d = this.viewMonth();
    return d.toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
  });

  readonly cells = computed(() =>
    buildMonthCells(this.viewMonth(), this.tasks(), this.events()),
  );

  readonly monthItemCount = computed(() =>
    this.cells().reduce(
      (n, cell) => n + (cell.inMonth ? cell.chips.length : 0),
      0,
    ),
  );

  readonly selectedDay = signal<string | null>(null);
  readonly canvasDetail = signal<CalendarEventDto | null>(null);
  deadlineTime = '';
  readonly savingDeadline = signal(false);
  readonly deadlineMessage = signal('');
  readonly deadlineTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  saveDeadlineTime(item: CalendarEventDto): void {
    if (this.savingDeadline() || !/^\d{2}:\d{2}$/.test(this.deadlineTime)) return;
    this.savingDeadline.set(true);
    this.deadlineMessage.set('');
    this.api.setCanvasDeadlineTime(item.id, this.deadlineTime, this.deadlineTimezone).subscribe({
      next: updated => {
        this.events.update(list => list.map(event => event.id === updated.id ? updated : event));
        if (this.canvasDetail()?.id === updated.id) this.canvasDetail.set(updated);
        this.savingDeadline.set(false);
        this.deadlineMessage.set('Deadline saved. Existing reminders keep their scheduled times; review them in Reminders.');
      },
      error: () => { this.savingDeadline.set(false); this.deadlineMessage.set('Could not save the deadline. Please try again.'); },
    });
  }
  readonly completingCanvas = signal(false);
  toggleCanvasComplete(item: CalendarEventDto): void {
    if (item.canvasKind !== 'DEADLINE' || this.completingCanvas()) return;
    const completed = !item.canvasCompleted;
    this.completingCanvas.set(true);
    this.error.set(null);
    this.api.setCanvasAssignmentCompleted(item.id, completed).subscribe({
      next: () => {
        this.events.update(list => list.map(event => event.id === item.id ? { ...event, canvasCompleted: completed } : event));
        if (this.canvasDetail()?.id === item.id) this.canvasDetail.set({ ...item, canvasCompleted: completed });
        this.completingCanvas.set(false);
      },
      error: () => { this.completingCanvas.set(false); this.error.set('Could not update assignment completion. Please try again.'); },
    });
  }
  readonly editor = signal<'new' | 'event' | 'task' | null>(null);
  readonly saving = signal(false);
  readonly formError = signal('');
  readonly success = signal('');
  readonly selectedItems = computed(
    () => this.cells().find((c) => c.key === this.selectedDay())?.chips ?? [],
  );
  title = '';
  startLocal = '';
  endLocal = '';
  dueDate = '';
  dueTime = '';
  allDay = false;
  entityId = '';
  private loadSubscription?: Subscription;
  private saveSubscription?: Subscription;
  ngOnInit(): void {
    this.reload();
  }
  ngOnDestroy(): void {
    this.loadSubscription?.unsubscribe();
    this.saveSubscription?.unsubscribe();
  }
  reload(): void {
    this.loadSubscription?.unsubscribe();
    this.loading.set(true);
    this.error.set(null);
    const { from, to } = monthRangeIso(this.viewMonth());
    this.loadSubscription = forkJoin({
      tasks: this.api.listTasks(),
      events: this.api.listCalendarEvents(from, to),
    }).subscribe({
      next: ({ tasks, events }) => {
        this.tasks.set(tasks);
        this.events.set(events);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load calendar data. Please retry.');
        this.loading.set(false);
      },
    });
  }
  prevMonth(): void {
    this.moveMonth(-1);
  }
  nextMonth(): void {
    this.moveMonth(1);
  }
  private moveMonth(offset: number): void {
    if (this.saving()) return;
    this.canvasDetail.set(null);
    const current = this.viewMonth();
    this.viewMonth.set(
      new Date(current.getFullYear(), current.getMonth() + offset, 1),
    );
    this.editor.set(null);
    this.selectedDay.set(null);
    this.reload();
  }
  selectDay(key: string): void {
    if (this.saving() || this.loading()) return;
    this.canvasDetail.set(null);
    this.selectedDay.set(key);
    this.editor.set(null);
    this.formError.set('');
    this.success.set('');
    this.revealPanel();
  }
  addBlock(key: string): void {
    if (this.saving()) return;
    this.canvasDetail.set(null);
    this.selectedDay.set(key);
    this.title = '';
    this.entityId = '';
    this.allDay = false;
    this.startLocal = key + 'T09:00';
    this.endLocal = key + 'T10:00';
    this.editor.set('new');
    this.formError.set('');
    this.success.set('');
    this.revealPanel();
  }
  editItem(chip: CalendarChip, day: string): void {
    if (this.saving() || this.loading()) return;
    this.canvasDetail.set(null);
    this.selectedDay.set(day);
    this.formError.set('');
    this.success.set('');
    this.entityId = chip.entityId;
    if (chip.kind === 'event') {
      const event = this.events().find((e) => e.id === chip.entityId);
      if (!event) return;
      if (event.canvasKind) {
        this.canvasDetail.set(event);
        this.deadlineTime = event.allDay ? '' : localInput(new Date(event.startAt)).slice(11, 16);
        this.deadlineMessage.set('');
        this.editor.set(null);
        this.revealPanel();
        return;
      }
      this.title = event.title;
      this.allDay = event.allDay;
      this.startLocal = localInput(new Date(event.startAt));
      this.endLocal = localInput(new Date(event.endAt));
      this.editor.set('event');
    } else {
      const task = this.tasks().find((t) => t.id === chip.entityId);
      if (!task) return;
      this.title = task.title;
      this.dueDate = task.dueDate ?? '';
      this.dueTime = task.dueTime?.slice(0, 5) ?? '';
      this.editor.set('task');
    }
    this.revealPanel();
  }
  save(): void {
    if (this.saving() || !this.editor()) return;
    this.formError.set('');
    this.success.set('');
    const title = this.title.trim();
    if (!title || title.length > 255) {
      this.formError.set('Enter a title of 1–255 characters.');
      return;
    }
    let request: Observable<TaskDto | CalendarEventDto>;
    if (this.editor() === 'task') {
      if (
        this.dueDate &&
        !validLocal(this.dueDate + 'T' + (this.dueTime || '12:00'))
      ) {
        this.formError.set('Choose a valid due date and time.');
        return;
      }
      if (!this.dueDate && this.dueTime) {
        this.formError.set('Choose a date for this due time.');
        return;
      }
      const task = this.tasks().find((t) => t.id === this.entityId);
      if (!task) {
        this.formError.set(
          'This task is no longer available. Reload the calendar.',
        );
        return;
      }
      // The API replaces editable fields, so retain values outside this editor.
      request = this.api.updateTask(this.entityId, {
        title,
        dueDate: this.dueDate || null,
        dueTime: this.dueTime || null,
        description: task.description,
        categoryId: task.categoryId,
        projectId: task.projectId,
        estimatedMinutes: task.estimatedMinutes,
        priority: task.priority,
        status: task.status,
      });
    } else {
      const start = validLocal(this.startLocal),
        end = validLocal(this.endLocal);
      if (!start || !end || end <= start) {
        this.formError.set('Choose valid times with the end after the start.');
        return;
      }
      const event = this.events().find((e) => e.id === this.entityId);
      const body = {
        title,
        startAt: start.toISOString(),
        endAt: end.toISOString(),
        allDay: this.allDay,
        description: event?.description ?? null,
        categoryId: event?.categoryId ?? null,
      };
      request =
        this.editor() === 'new'
          ? this.api.createCalendarEvent(body)
          : this.api.updateCalendarEvent(this.entityId, body);
    }
    this.saving.set(true);
    this.saveSubscription = request.subscribe({
      next: () => {
        this.saving.set(false);
        this.editor.set(null);
        this.success.set('Saved. Your calendar is up to date.');
        this.reload();
      },
      error: () => {
        this.saving.set(false);
        this.formError.set(
          'Could not save your changes. They are still here; please try again.',
        );
      },
    });
  }
}

function startOfMonth(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), 1);
}

function toLocalDateKey(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

function dueDateKey(dueDate: string): string {
  if (/^\d{4}-\d{2}-\d{2}$/.test(dueDate)) {
    return dueDate;
  }
  return toLocalDateKey(new Date(dueDate));
}

function mondayIndex(date: Date): number {
  return (date.getDay() + 6) % 7;
}

function monthRangeIso(viewMonth: Date): { from: string; to: string } {
  const from = new Date(
    viewMonth.getFullYear(),
    viewMonth.getMonth(),
    1,
    0,
    0,
    0,
    0,
  );
  const to = new Date(
    viewMonth.getFullYear(),
    viewMonth.getMonth() + 1,
    1,
    0,
    0,
    0,
    0,
  );
  return { from: from.toISOString(), to: to.toISOString() };
}

function buildMonthCells(
  viewMonth: Date,
  tasks: TaskDto[],
  events: CalendarEventDto[],
): CalendarCell[] {
  const year = viewMonth.getFullYear();
  const month = viewMonth.getMonth();
  const first = new Date(year, month, 1);
  const daysInMonth = new Date(year, month + 1, 0).getDate();
  const lead = mondayIndex(first);
  const todayKey = toLocalDateKey(new Date());

  const byDay = new Map<string, CalendarChip[]>();

  const push = (key: string, chip: CalendarChip): void => {
    const list = byDay.get(key) ?? [];
    if (!list.some((item) => item.id === chip.id)) {
      list.push(chip);
      byDay.set(key, list);
    }
  };

  for (const task of tasks) {
    if (!task.dueDate) {
      continue;
    }
    const key = dueDateKey(task.dueDate);
    push(key, {
      id: `task-${task.id}`,
      label: task.title,
      kind: 'task',
      entityId: task.id,
    });
  }

  for (const event of events) {
    const keys = eventDayKeys(event);
    for (const key of keys) {
      push(key, {
        id: `event-${event.id}-${key}`,
        label: (event.canvasCompleted ? '✓ ' : '') + event.title,
        kind: 'event',
        canvasKind: event.canvasKind,
        entityId: event.id,
      });
    }
  }

  const totalCells = Math.ceil((lead + daysInMonth) / 7) * 7;
  const cells: CalendarCell[] = [];

  for (let i = 0; i < totalCells; i++) {
    const dayNum = i - lead + 1;
    if (dayNum < 1 || dayNum > daysInMonth) {
      cells.push({
        key: `pad-${i}`,
        day: null,
        inMonth: false,
        isToday: false,
        chips: [],
      });
      continue;
    }
    const key = toLocalDateKey(new Date(year, month, dayNum));
    cells.push({
      key,
      day: dayNum,
      inMonth: true,
      isToday: key === todayKey,
      chips: byDay.get(key) ?? [],
    });
  }

  return cells;
}

export function eventDayKeys(event: CalendarEventDto): string[] {
  if (event.canvasStartDate && event.canvasEndDate) {
    const keys: string[] = [];
    let cursor = event.canvasStartDate;
    while (cursor < event.canvasEndDate && keys.length < 400) {
      keys.push(cursor);
      const [year, month, day] = cursor.split('-').map(Number);
      cursor = toLocalDateKey(new Date(year, month - 1, day + 1));
    }
    return keys;
  }
  const start = new Date(event.startAt),
    end = new Date(event.endAt);
  if (
    !Number.isFinite(start.getTime()) ||
    !Number.isFinite(end.getTime()) ||
    end <= start
  )
    return [];
  const keys: string[] = [];
  let cursor = new Date(start.getFullYear(), start.getMonth(), start.getDate());
  while (cursor < end) {
    keys.push(toLocalDateKey(cursor));
    cursor = new Date(
      cursor.getFullYear(),
      cursor.getMonth(),
      cursor.getDate() + 1,
    );
  }
  return keys;
}
