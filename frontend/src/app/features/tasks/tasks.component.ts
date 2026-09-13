import { canvasDeadlineTask } from '../../core/api/canvas-deadline';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { forkJoin } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import {
  CalendarEventDto,
  TaskDto,
  TaskPriority,
  TaskStatus,
} from '../../core/api/api.models';

const EVENT_LOOKAHEAD_DAYS = 30;

@Component({
  selector: 'app-tasks',
  standalone: true,
  imports: [FormsModule, ReactiveFormsModule, MatButtonModule, MatIconModule],
  templateUrl: './tasks.component.html',
  styleUrl: './tasks.component.scss',
})
export class TasksComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly fb = inject(FormBuilder);
  private readonly route = inject(ActivatedRoute);

  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly submittingEvent = signal(false);
  readonly editingId = signal<string | null>(null);
  readonly editingEventId = signal<string | null>(null);
  readonly savingEventEdit = signal(false);
  readonly eventEditError = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  readonly eventFormError = signal<string | null>(null);
  readonly eventFormSuccess = signal<string | null>(null);
  readonly tasks = signal<TaskDto[]>([]);
  readonly events = signal<CalendarEventDto[]>([]);
  readonly completingCanvasId = signal<string | null>(null);
  toggleCanvasComplete(event: CalendarEventDto): void {
    if (event.canvasKind !== 'DEADLINE' || this.completingCanvasId()) return;
    const completed = !event.canvasCompleted;
    this.completingCanvasId.set(event.id);
    this.error.set(null);
    this.api.setCanvasAssignmentCompleted(event.id, completed).subscribe({
      next: () => {
        this.events.update(list => list.map(item => item.id === event.id ? { ...item, canvasCompleted: completed } : item));
        this.completingCanvasId.set(null);
      },
      error: () => { this.completingCanvasId.set(null); this.error.set('Could not update assignment completion. Please try again.'); },
    });
  }
  readonly search = signal('');
  readonly view = signal('open');
  readonly sort = signal('due');
  readonly visibleTasks = computed(() => {
    const query = this.search().trim().toLocaleLowerCase();
    const today = toDatetimeLocalValue(new Date()).slice(0, 10);
    const weekStart = new Date();
    weekStart.setDate(weekStart.getDate() - (weekStart.getDay() + 6) % 7);
    const weekStartKey = toDatetimeLocalValue(weekStart).slice(0, 10);
    const weekEnd = new Date(weekStart);
    weekEnd.setDate(weekEnd.getDate() + 7);
    const weekEndKey = toDatetimeLocalValue(weekEnd).slice(0, 10);
    const priorityRank = { URGENT: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };
    return this.tasks().filter(task => {
      const open = task.status === 'TODO' || task.status === 'IN_PROGRESS';
      const matchesView = this.view() === 'all'
        || (this.view() === 'open' && open)
        || (this.view() === 'completed' && task.status === 'COMPLETED')
        || (this.view() === 'today' && open && task.dueDate === today)
        || (this.view() === 'week' && open && !!task.dueDate && task.dueDate >= weekStartKey && task.dueDate < weekEndKey)
        || (this.view() === 'overdue' && open && !!task.dueDate && (task.dueDate < today
          || (task.dueDate === today && !!task.dueTime && new Date(`${task.dueDate}T${task.dueTime}`).getTime() < Date.now())));
      return matchesView && `${task.title} ${task.description ?? ''}`.toLocaleLowerCase().includes(query);
    }).sort((a, b) => {
      if (this.sort() === 'title') return a.title.localeCompare(b.title);
      if (this.sort() === 'priority') {
        const diff = priorityRank[a.priority] - priorityRank[b.priority];
        if (diff) return diff;
      }
      const due = (task: TaskDto) => `${task.dueDate ?? '9999-12-31'}T${task.dueTime ?? '23:59:59'}`;
      return due(a).localeCompare(due(b)) || a.title.localeCompare(b.title);
    });
  });

  clearFilters(): void {
    this.search.set('');
    this.view.set('all');
    this.sort.set('due');
  }

  statusLabel(status: TaskStatus): string {
    return { TODO: 'To do', IN_PROGRESS: 'In progress', COMPLETED: 'Completed', CANCELLED: 'Cancelled' }[status];
  }

  readonly priorities: TaskPriority[] = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'];
  readonly statuses: TaskStatus[] = ['TODO', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'];

  readonly form = this.fb.nonNullable.group({
    title: ['', [Validators.required, Validators.maxLength(255)]],
    dueDate: [''],
    dueTime: [''],
    priority: ['MEDIUM' as TaskPriority, Validators.required],
    status: ['TODO' as TaskStatus, Validators.required],
  });

  readonly editForm = this.fb.nonNullable.group({
    title: ['', [Validators.required, Validators.maxLength(255)]],
    dueDate: [''],
    dueTime: [''],
    priority: ['MEDIUM' as TaskPriority, Validators.required],
    status: ['TODO' as TaskStatus, Validators.required],
  });

  readonly eventForm = this.fb.nonNullable.group({
    title: ['', [Validators.required, Validators.maxLength(255)]],
    startLocal: ['', Validators.required],
    endLocal: ['', Validators.required],
  });

  readonly upcomingEvents = computed(() =>
    this.events().filter(event => !event.canvasKind && inEventWindow(event)).sort(
      (a, b) => new Date(a.startAt).getTime() - new Date(b.startAt).getTime(),
    ),
  );

  readonly canvasAssignments = computed(() => this.events().filter(event => {
    if (event.canvasKind !== 'DEADLINE') return false;
    const task = canvasDeadlineTask(event);
    const today = toDatetimeLocalValue(new Date()).slice(0, 10);
    const weekStart = new Date();
    weekStart.setDate(weekStart.getDate() - (weekStart.getDay() + 6) % 7);
    const weekEnd = new Date(weekStart); weekEnd.setDate(weekEnd.getDate() + 7);
    const due = task.dueDate!;
    const open = !event.canvasCompleted;
    const matches = this.view() === 'all' || (this.view() === 'open' && open)
      || (this.view() === 'completed' && !open)
      || (this.view() === 'today' && open && due === today)
      || (this.view() === 'week' && open && due >= toDatetimeLocalValue(weekStart).slice(0, 10) && due < toDatetimeLocalValue(weekEnd).slice(0, 10))
      || (this.view() === 'overdue' && open && (due < today || (!!task.dueTime && new Date(event.startAt).getTime() < Date.now())));
    return matches && `${task.title} ${task.description ?? ''}`.toLocaleLowerCase().includes(this.search().trim().toLocaleLowerCase());
  })
    .sort((a, b) => a.startAt.localeCompare(b.startAt)));
  readonly canvasEvents = computed(() => this.events().filter(event => event.canvasKind === 'EVENT' && inEventWindow(event))
    .sort((a, b) => a.startAt.localeCompare(b.startAt)));
  readonly eventEditForm = this.fb.nonNullable.group({
    title: ['', [Validators.required, Validators.maxLength(255)]],
    startLocal: ['', Validators.required],
    endLocal: ['', Validators.required],
    allDay: [false],
  });

  startEventEdit(event: CalendarEventDto): void {
    if (event.canvasKind || this.savingEventEdit()) return;
    this.editingEventId.set(event.id);
    this.eventEditError.set(null);
    this.eventEditForm.reset({ title: event.title, startLocal: toDatetimeLocalValue(new Date(event.startAt)),
      endLocal: toDatetimeLocalValue(new Date(event.endAt)), allDay: event.allDay });
  }

  cancelEventEdit(): void {
    if (!this.savingEventEdit()) this.editingEventId.set(null);
  }

  saveEventEdit(event: CalendarEventDto): void {
    if (event.canvasKind || this.savingEventEdit()) return;
    this.eventEditForm.controls.title.setValue(this.eventEditForm.controls.title.value.trim());
    this.eventEditError.set(null);
    if (this.eventEditForm.invalid) {
      this.eventEditForm.markAllAsTouched();
      this.eventEditError.set('Enter a title and valid start and end times.');
      return;
    }
    const { title, startLocal, endLocal, allDay } = this.eventEditForm.getRawValue();
    const startAt = localInputToIso(startLocal), endAt = localInputToIso(endLocal);
    if (!startAt || !endAt || new Date(endAt) <= new Date(startAt)) {
      this.eventEditError.set('End must be after start.');
      return;
    }
    this.savingEventEdit.set(true);
    this.api.updateCalendarEvent(event.id, { title, startAt, endAt, allDay,
      description: event.description, categoryId: event.categoryId }).subscribe({
      next: updated => {
        this.events.update(list => list.map(item => item.id === updated.id ? updated : item));
        this.savingEventEdit.set(false);
        this.editingEventId.set(null);
      },
      error: () => {
        this.savingEventEdit.set(false);
        this.eventEditError.set('Could not update the time block. Your changes are still here; try again.');
      },
    });
  }

  ngOnInit(): void {
    const view = this.route.snapshot.queryParamMap.get('view');
    if (view && ['all', 'open', 'completed', 'today', 'week', 'overdue'].includes(view)) this.view.set(view);
    this.prefillEventTimes();
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
          this.error.set('Could not load tasks and time blocks.');
        }
        this.loading.set(false);
      },
    });
  }

  submit(): void {
    if (this.saving()) return;
    this.form.controls.title.setValue(this.form.controls.title.value.trim());
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.saving.set(true);
    this.error.set(null);
    const payload = this.taskPayload(this.form.getRawValue());
    this.api.createTask(payload).subscribe({
      next: (created) => {
        this.tasks.update((list) => [created, ...list]);
        this.form.reset({ title: '', dueDate: '', dueTime: '', priority: 'MEDIUM', status: 'TODO' });
        this.saving.set(false);
      },
      error: (err) => {
        const statusCode = err?.status as number | undefined;
        this.error.set(
          statusCode === 0
            ? 'Cannot reach the API. Is the backend running on port 8080?'
            : 'Could not create task.',
        );
        this.saving.set(false);
      },
    });
  }

  submitEvent(): void {
    if (this.submittingEvent()) return;
    this.eventForm.controls.title.setValue(this.eventForm.controls.title.value.trim());
    this.eventFormError.set(null);
    this.eventFormSuccess.set(null);

    if (this.eventForm.invalid) {
      this.eventForm.markAllAsTouched();
      return;
    }

    const { title, startLocal, endLocal } = this.eventForm.getRawValue();
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

    this.submittingEvent.set(true);
    this.api
      .createCalendarEvent({ title: title.trim(), startAt, endAt, allDay: false })
      .subscribe({
        next: (created) => {
          this.events.update((list) =>
            [...list, created].sort(
              (a, b) => new Date(a.startAt).getTime() - new Date(b.startAt).getTime(),
            ),
          );
          this.eventFormSuccess.set('Time block added.');
          this.eventForm.controls.title.reset('');
          this.prefillEventTimes();
          this.submittingEvent.set(false);
        },
        error: (err) => {
          const statusCode = err?.status as number | undefined;
          this.eventFormError.set(
            statusCode === 0
              ? 'Cannot reach the API. Is the backend running on port 8080?'
              : 'Could not save the time block. Try again.',
          );
          this.submittingEvent.set(false);
        },
      });
  }

  removeEvent(event: CalendarEventDto): void {
    if (event.canvasKind || this.savingEventEdit()) return;
    this.error.set(null);
    this.api.deleteCalendarEvent(event.id).subscribe({
      next: () => {
        this.events.update((list) => list.filter((item) => item.id !== event.id));
      },
      error: () => this.error.set('Could not delete time block.'),
    });
  }

  startEdit(task: TaskDto): void {
    this.editingId.set(task.id);
    this.editForm.reset({
      title: task.title,
      dueDate: task.dueDate ?? '',
      dueTime: toTimeInputValue(task.dueTime),
      priority: task.priority,
      status: task.status,
    });
  }

  cancelEdit(): void {
    this.editingId.set(null);
  }

  saveEdit(task: TaskDto): void {
    this.editForm.controls.title.setValue(this.editForm.controls.title.value.trim());
    if (this.editForm.invalid) {
      this.editForm.markAllAsTouched();
      return;
    }

    this.error.set(null);
    const payload = this.taskPayload(this.editForm.getRawValue());
    this.api
      .updateTask(task.id, {
        ...payload,
        description: task.description,
        categoryId: task.categoryId,
        projectId: task.projectId,
        estimatedMinutes: task.estimatedMinutes,
      })
      .subscribe({
        next: (updated) => {
          this.tasks.update((list) => list.map((item) => (item.id === updated.id ? updated : item)));
          this.editingId.set(null);
        },
        error: () => this.error.set('Could not update task.'),
      });
  }

  toggleComplete(task: TaskDto): void {
    const nextStatus: TaskStatus = task.status === 'COMPLETED' ? 'TODO' : 'COMPLETED';
    this.api
      .updateTask(task.id, {
        title: task.title,
        description: task.description,
        categoryId: task.categoryId,
        projectId: task.projectId,
        dueDate: task.dueDate,
        dueTime: task.dueTime,
        estimatedMinutes: task.estimatedMinutes,
        priority: task.priority,
        status: nextStatus,
      })
      .subscribe({
        next: (updated) => {
          this.tasks.update((list) => list.map((item) => (item.id === updated.id ? updated : item)));
        },
        error: () => this.error.set('Could not update task.'),
      });
  }

  remove(task: TaskDto): void {
    this.error.set(null);
    this.api.deleteTask(task.id).subscribe({
      next: () => {
        this.tasks.update((list) => list.filter((item) => item.id !== task.id));
        if (this.editingId() === task.id) {
          this.editingId.set(null);
        }
      },
      error: () => this.error.set('Could not delete task.'),
    });
  }

  formatDue(task: TaskDto): string {
    if (!task.dueDate) {
      return '';
    }
    if (!task.dueTime) {
      return task.dueDate;
    }
    const [hh, mm] = task.dueTime.slice(0, 5).split(':').map(Number);
    const date = new Date(2000, 0, 1, hh, mm, 0, 0);
    return `${task.dueDate} at ${date.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })}`;
  }

  formatEventWhen(event: CalendarEventDto): string {
    if (event.allDay) return `${event.canvasStartDate ?? toDatetimeLocalValue(new Date(event.startAt)).slice(0, 10)} · ${event.canvasKind === 'DEADLINE' ? 'Time not provided by Canvas' : 'All day'}`;
    const start = new Date(event.startAt);
    const end = new Date(event.endAt);
    const date = start.toLocaleDateString(undefined, {
      weekday: 'short',
      month: 'short',
      day: 'numeric',
    });
    const time = (d: Date) =>
      d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
    if (event.canvasKind === 'DEADLINE') return `${date} at ${time(start)}`;
    const endDate = end.toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' });
    return `${date} · ${time(start)} – ${date === endDate ? '' : endDate + ' · '}${time(end)}`;
  }

  private prefillEventTimes(): void {
    const start = new Date();
    start.setMinutes(0, 0, 0);
    start.setHours(start.getHours() + 1);
    const end = new Date(start);
    end.setHours(end.getHours() + 1);
    this.eventForm.patchValue({
      startLocal: toDatetimeLocalValue(start),
      endLocal: toDatetimeLocalValue(end),
    });
  }

  private taskPayload(raw: {
    title: string;
    dueDate: string;
    dueTime: string;
    priority: TaskPriority;
    status: TaskStatus;
  }) {
    return {
      title: raw.title.trim(),
      priority: raw.priority,
      status: raw.status,
      ...(raw.dueDate ? { dueDate: raw.dueDate } : { dueDate: null }),
      ...(raw.dueTime ? { dueTime: raw.dueTime } : { dueTime: null }),
    };
  }
}

function eventRangeIso(): { from: string; to: string } {
  const from = new Date();
  from.setHours(0, 0, 0, 0);
  const to = new Date(from);
  to.setDate(to.getDate() + EVENT_LOOKAHEAD_DAYS + 1);
  return { from: from.toISOString(), to: to.toISOString() };
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

function toTimeInputValue(dueTime: string | null): string {
  if (!dueTime) {
    return '';
  }
  return dueTime.length >= 5 ? dueTime.slice(0, 5) : dueTime;
}

function inEventWindow(event: CalendarEventDto): boolean {
  const { from, to } = eventRangeIso();
  return event.endAt > from && event.startAt < to;
}
