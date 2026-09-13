import {
  AfterViewChecked,
  Component,
  ElementRef,
  OnInit,
  ViewChild,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { forkJoin } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import {
  CalendarEventDto,
  TaskDto,
  TaskPriority,
} from '../../core/api/api.models';

export interface TimelineItem {
  id: string;
  kind: 'event' | 'task';
  title: string;
  start: Date;
  end: Date;
  eventId?: string;
  subtitle?: string;
  priority?: TaskPriority;
  completed?: boolean;
}

export interface TimelineLayoutItem extends TimelineItem {
  column: number;
  columnCount: number;
}

const DAY_START_HOUR = 0;
const DAY_END_HOUR = 24;
const HOUR_HEIGHT_PX = 52;
const HOUR_COUNT = DAY_END_HOUR - DAY_START_HOUR;
const TIMELINE_HEIGHT_PX = HOUR_HEIGHT_PX * HOUR_COUNT;
const GUTTER = '0.35rem';
const COLUMN_GAP_PX = 4;

@Component({
  selector: 'app-today',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatIconModule],
  templateUrl: './today.component.html',
  styleUrl: './today.component.scss',
})
export class TodayComponent implements OnInit, AfterViewChecked {
  private readonly api = inject(ApiService);
  private readonly fb = inject(FormBuilder);

  @ViewChild('timelineScroll') private timelineScroll?: ElementRef<HTMLElement>;

  private pendingTimelineScroll = false;

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly viewDate = signal(startOfDay(new Date()));
  readonly tasks = signal<TaskDto[]>([]);
  readonly events = signal<CalendarEventDto[]>([]);
  readonly editingEventId = signal<string | null>(null);
  readonly savingEvent = signal(false);
  readonly eventFormError = signal<string | null>(null);

  readonly eventTimeForm = this.fb.nonNullable.group({
    startLocal: ['', Validators.required],
    endLocal: ['', Validators.required],
  });

  readonly hourHeightPx = HOUR_HEIGHT_PX;
  readonly hourCount = HOUR_COUNT;
  readonly timelineHeightPx = TIMELINE_HEIGHT_PX;
  readonly hourLabels = Array.from(
    { length: HOUR_COUNT },
    (_, i) => DAY_START_HOUR + i,
  );

  readonly dayLabel = computed(() =>
    this.viewDate().toLocaleDateString(undefined, {
      weekday: 'long',
      month: 'long',
      day: 'numeric',
    }),
  );

  readonly isToday = computed(() => dateKeysEqual(this.viewDate(), new Date()));

  readonly timelineItems = computed(() =>
    buildTimelineItems(this.viewDate(), this.tasks(), this.events()),
  );

  readonly timelineLayouts = computed(() => layoutOverlappingItems(this.timelineItems()));

  readonly unscheduledTasks = computed(() => {
    const key = toLocalDateKey(this.viewDate());

    return this.tasks()
      .filter((task) => {
        if (!task.dueDate || dueDateKey(task.dueDate) !== key) {
          return false;
        }
        if (task.status === 'COMPLETED' || task.status === 'CANCELLED') {
          return false;
        }
        return !task.dueTime;
      })
      .sort((a, b) => priorityRank(a.priority) - priorityRank(b.priority));
  });

  readonly untimedCanvas = computed(() => this.events().filter(event =>
    event.canvasKind === 'DEADLINE' && event.allDay && !event.canvasCompleted &&
    event.canvasStartDate === toLocalDateKey(this.viewDate())));

  readonly hasAnything = computed(
    () => this.timelineItems().length > 0 || this.unscheduledTasks().length > 0 || this.untimedCanvas().length > 0,
  );

  readonly editingEvent = computed(() => {
    const id = this.editingEventId();
    if (!id) {
      return null;
    }
    return this.events().find((event) => event.id === id) ?? null;
  });

  ngOnInit(): void {
    this.reload();
  }

  ngAfterViewChecked(): void {
    if (!this.pendingTimelineScroll) {
      return;
    }
    this.pendingTimelineScroll = false;
    this.scrollTimelineIntoView();
  }

  reload(): void {
    this.loading.set(true);
    this.error.set(null);
    const { from, to } = dayRangeIso(this.viewDate());

    forkJoin({
      tasks: this.api.listTasks(),
      events: this.api.listCalendarEvents(from, to),
    }).subscribe({
      next: ({ tasks, events }) => {
        this.tasks.set(tasks);
        this.events.set(events);
        this.loading.set(false);
        this.pendingTimelineScroll = true;
      },
      error: (err) => {
        const statusCode = err?.status as number | undefined;
        if (statusCode === 401) {
          this.error.set('Your session expired. Redirecting to login…');
        } else if (statusCode === 0) {
          this.error.set('Cannot reach the API. Is the backend running on port 8080?');
        } else {
          this.error.set('Could not load today\'s schedule.');
        }
        this.loading.set(false);
      },
    });
  }

  goToToday(): void {
    this.viewDate.set(startOfDay(new Date()));
    this.reload();
  }

  prevDay(): void {
    this.cancelEventEdit();
    this.viewDate.update((d) => addDays(d, -1));
    this.reload();
  }

  nextDay(): void {
    this.cancelEventEdit();
    this.viewDate.update((d) => addDays(d, 1));
    this.reload();
  }

  formatHour(hour: number): string {
    const date = new Date(2000, 0, 1, hour, 0, 0, 0);
    return date.toLocaleTimeString(undefined, { hour: 'numeric' });
  }

  itemStyle(item: TimelineLayoutItem): Record<string, string> {
    const total = totalDayMinutes(DAY_START_HOUR, DAY_END_HOUR);
    const startMin = minutesFromDayStart(item.start, DAY_START_HOUR, DAY_END_HOUR);
    const top = (clamp(startMin, 0, total) / total) * 100;

    let height: string;
    let minHeight: string;

    if (item.kind === 'task') {
      const minHeightPx = 44;
      height = `${(minHeightPx / TIMELINE_HEIGHT_PX) * 100}%`;
      minHeight = `${minHeightPx}px`;
    } else {
      const endMin = minutesFromDayStart(item.end, DAY_START_HOUR, DAY_END_HOUR);
      const clampedStart = clamp(startMin, 0, total);
      const clampedEnd = clamp(
        Math.max(endMin, clampedStart + 30),
        clampedStart + 30,
        total,
      );
      const duration = clampedEnd - clampedStart;
      const heightPct = (duration / total) * 100;
      const minHeightPx = duration <= 45 ? 56 : 72;
      height = `${Math.max(heightPct, (minHeightPx / TIMELINE_HEIGHT_PX) * 100)}%`;
      minHeight = `${minHeightPx}px`;
    }

    const fraction = 1 / item.columnCount;
    const gapPx = item.columnCount > 1 ? COLUMN_GAP_PX : 0;

    return {
      top: `${top}%`,
      height,
      minHeight,
      left: `calc(${GUTTER} + (100% - ${GUTTER} * 2) * ${item.column * fraction})`,
      width: `calc((100% - ${GUTTER} * 2) * ${fraction} - ${gapPx}px)`,
    };
  }

  private scrollTimelineIntoView(): void {
    const el = this.timelineScroll?.nativeElement;
    if (!el) {
      return;
    }

    const items = this.timelineItems();
    if (items.length === 0) {
      if (this.isToday()) {
        el.scrollTop = Math.max(0, (new Date().getHours() - 2) * HOUR_HEIGHT_PX);
      }
      return;
    }

    const firstHour = items[0].start.getHours();
    const lastEnd = items[items.length - 1].end;
    const lastHour =
      lastEnd.getMinutes() > 0 || lastEnd.getSeconds() > 0
        ? lastEnd.getHours() + 1
        : lastEnd.getHours();
    const viewportHours = Math.max(1, Math.ceil(el.clientHeight / HOUR_HEIGHT_PX));
    const focusHour = Math.max(0, lastHour - viewportHours + 2);
    const scrollHour = Math.min(Math.max(0, firstHour - 1), focusHour);

    el.scrollTop = scrollHour * HOUR_HEIGHT_PX;
  }

  isCompact(item: TimelineItem): boolean {
    if (item.kind === 'task') {
      return true;
    }
    const durationMs = item.end.getTime() - item.start.getTime();
    return durationMs <= 45 * 60_000;
  }

  formatTime(date: Date): string {
    return date.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
  }

  formatTimeLabel(item: TimelineItem): string {
    const event = this.events().find(event => event.id === item.eventId);
    if (event?.canvasKind === 'DEADLINE') return `Due ${this.formatTime(item.start)}`;
    if (item.kind === 'task') {
      return this.formatTime(item.start);
    }
    return `${this.formatTime(item.start)} – ${this.formatTime(item.end)}`;
  }

  kindLabel(kind: TimelineItem['kind']): string {
    return kind === 'event' ? 'Event' : 'Task';
  }

  selectEvent(item: TimelineItem): void {
    if (item.kind !== 'event' || !item.eventId) {
      return;
    }
    const event = this.events().find((entry) => entry.id === item.eventId);
    if (!event) {
      return;
    }
    this.editingEventId.set(item.eventId);
    this.eventFormError.set(null);
    this.eventTimeForm.patchValue({
      startLocal: toDatetimeLocalValue(new Date(event.startAt)),
      endLocal: toDatetimeLocalValue(new Date(event.endAt)),
    });
  }

  cancelEventEdit(): void {
    this.editingEventId.set(null);
    this.eventFormError.set(null);
  }

  saveEventTime(): void {
    const eventId = this.editingEventId();
    if (!eventId) {
      return;
    }
    this.eventFormError.set(null);

    if (this.eventTimeForm.invalid) {
      this.eventTimeForm.markAllAsTouched();
      return;
    }

    const { startLocal, endLocal } = this.eventTimeForm.getRawValue();
    const startAt = localInputToIso(startLocal);
    const endAt = localInputToIso(endLocal);

    if (!startAt || !endAt) {
      this.eventFormError.set('Enter valid start and end times.');
      return;
    }
    if (new Date(endAt).getTime() <= new Date(startAt).getTime()) {
      this.eventFormError.set('End must be after start.');
      return;
    }

    this.savingEvent.set(true);
    this.api.updateCalendarEvent(eventId, { startAt, endAt }).subscribe({
      next: (updated) => {
        this.events.update((list) =>
          list.map((event) => (event.id === updated.id ? updated : event)),
        );
        this.savingEvent.set(false);
        this.cancelEventEdit();
      },
      error: (err) => {
        const statusCode = err?.status as number | undefined;
        this.eventFormError.set(
          statusCode === 0
            ? 'Cannot reach the API. Is the backend running on port 8080?'
            : 'Could not update event time. Try again.',
        );
        this.savingEvent.set(false);
      },
    });
  }
}

function buildTimelineItems(
  viewDate: Date,
  tasks: TaskDto[],
  events: CalendarEventDto[],
): TimelineItem[] {
  const dayKey = toLocalDateKey(viewDate);
  const items: TimelineItem[] = [];

  for (const event of events) {
    if (event.canvasKind === 'DEADLINE' && (event.allDay || event.canvasCompleted)) continue;
    if (!instantOverlapsDay(event.startAt, event.endAt, dayKey)) {
      continue;
    }
    const start = new Date(event.startAt);
    const end = new Date(event.endAt);
    items.push({
      id: `event-${event.id}`,
      kind: 'event',
      title: event.title,
      start,
      end: end.getTime() > start.getTime() ? end : addMinutes(start, 30),
      eventId: event.id,
      subtitle: event.description ?? undefined,
    });
  }

  for (const task of tasks) {
    if (!task.dueDate || dueDateKey(task.dueDate) !== dayKey || !task.dueTime) {
      continue;
    }
    if (task.status === 'COMPLETED' || task.status === 'CANCELLED') {
      continue;
    }
    const start = taskDueDateTime(task.dueDate, task.dueTime);
    if (!start) {
      continue;
    }
    items.push({
      id: `task-${task.id}`,
      kind: 'task',
      title: task.title,
      start,
      end: start,
      priority: task.priority,
    });
  }

  return items.sort((a, b) => a.start.getTime() - b.start.getTime());
}

function layoutOverlappingItems(items: TimelineItem[]): TimelineLayoutItem[] {
  if (items.length === 0) {
    return [];
  }

  const sorted = [...items].sort((a, b) => {
    const diff = a.start.getTime() - b.start.getTime();
    if (diff !== 0) {
      return diff;
    }
    return a.id.localeCompare(b.id);
  });

  const layouts: TimelineLayoutItem[] = [];
  let cluster: TimelineItem[] = [];
  let clusterEnd = 0;

  const flushCluster = (): void => {
    if (cluster.length === 0) {
      return;
    }
    layouts.push(...assignTimelineColumns(cluster));
    cluster = [];
    clusterEnd = 0;
  };

  for (const item of sorted) {
    const start = item.start.getTime();
    const end = timelineItemEnd(item).getTime();
    if (cluster.length === 0 || start < clusterEnd) {
      cluster.push(item);
      clusterEnd = Math.max(clusterEnd, end);
    } else {
      flushCluster();
      cluster.push(item);
      clusterEnd = end;
    }
  }
  flushCluster();

  return layouts;
}

function assignTimelineColumns(cluster: TimelineItem[]): TimelineLayoutItem[] {
  const sorted = [...cluster].sort((a, b) => a.start.getTime() - b.start.getTime());
  const columnEnds: number[] = [];
  const placements: { item: TimelineItem; column: number }[] = [];

  for (const item of sorted) {
    const start = item.start.getTime();
    const end = timelineItemEnd(item).getTime();
    let column = columnEnds.findIndex((columnEnd) => columnEnd <= start);
    if (column === -1) {
      column = columnEnds.length;
      columnEnds.push(end);
    } else {
      columnEnds[column] = end;
    }
    placements.push({ item, column });
  }

  const columnCount = Math.max(1, columnEnds.length);
  return placements.map(({ item, column }) => ({
    ...item,
    column,
    columnCount,
  }));
}

function timelineItemEnd(item: TimelineItem): Date {
  if (item.kind === 'task') {
    return addMinutes(item.start, 30);
  }
  return item.end.getTime() > item.start.getTime() ? item.end : addMinutes(item.start, 30);
}

function taskDueDateTime(dueDate: string, dueTime: string): Date | null {
  const datePart = dueDateKey(dueDate);
  const timePart = dueTime.length >= 5 ? dueTime.slice(0, 5) : dueTime;
  const [y, m, d] = datePart.split('-').map(Number);
  const [hh, mm] = timePart.split(':').map(Number);
  if ([y, m, d, hh, mm].some((n) => Number.isNaN(n))) {
    return null;
  }
  return new Date(y, m - 1, d, hh, mm, 0, 0);
}

function priorityRank(priority: TaskPriority): number {
  const rank: Record<TaskPriority, number> = {
    URGENT: 0,
    HIGH: 1,
    MEDIUM: 2,
    LOW: 3,
  };
  return rank[priority];
}

function startOfDay(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

function addDays(date: Date, days: number): Date {
  const d = new Date(date);
  d.setDate(d.getDate() + days);
  return d;
}

function addMinutes(date: Date, minutes: number): Date {
  return new Date(date.getTime() + minutes * 60_000);
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

function dateKeysEqual(a: Date, b: Date): boolean {
  return toLocalDateKey(a) === toLocalDateKey(b);
}

function dayRangeIso(viewDate: Date): { from: string; to: string } {
  const from = new Date(viewDate.getFullYear(), viewDate.getMonth(), viewDate.getDate(), 0, 0, 0, 0);
  const to = new Date(viewDate.getFullYear(), viewDate.getMonth(), viewDate.getDate() + 1, 0, 0, 0, 0);
  return { from: from.toISOString(), to: to.toISOString() };
}

function dayBounds(dayKey: string): { start: number; end: number } {
  const [y, m, d] = dayKey.split('-').map(Number);
  const start = new Date(y, m - 1, d, 0, 0, 0, 0).getTime();
  const end = new Date(y, m - 1, d + 1, 0, 0, 0, 0).getTime();
  return { start, end };
}

function instantOverlapsDay(startAt: string, endAt: string, dayKey: string): boolean {
  const { start: dayStart, end: dayEnd } = dayBounds(dayKey);
  const start = new Date(startAt).getTime();
  const end = new Date(endAt).getTime();
  return start < dayEnd && end > dayStart;
}

function totalDayMinutes(startHour: number, endHour: number): number {
  return (endHour - startHour) * 60;
}

function minutesFromDayStart(date: Date, startHour: number, endHour: number): number {
  const dayStart = new Date(date.getFullYear(), date.getMonth(), date.getDate(), startHour, 0, 0, 0);
  const minutes = (date.getTime() - dayStart.getTime()) / 60_000;
  return clamp(minutes, 0, totalDayMinutes(startHour, endHour));
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function localInputToIso(value: string): string | null {
  if (!value) {
    return null;
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  return date.toISOString();
}

function toDatetimeLocalValue(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  const h = String(date.getHours()).padStart(2, '0');
  const min = String(date.getMinutes()).padStart(2, '0');
  return `${y}-${m}-${d}T${h}:${min}`;
}
