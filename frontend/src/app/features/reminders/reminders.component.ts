import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { forkJoin, from, of, concatMap, catchError, map, toArray } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import {
  CalendarEventDto,
  NotificationChannel,
  ReminderDto,
  TaskDto,
} from '../../core/api/api.models';

export interface ReminderOffsetOption {
  minutes: number;
  label: string;
}

export interface ReminderGroup {
  key: string;
  title: string;
  eventAtLabel: string;
  reminders: ReminderDto[];
}

const OFFSET_OPTIONS: ReminderOffsetOption[] = [
  { minutes: 10_080, label: '1 week before' },
  { minutes: 1_440, label: '1 day before' },
  { minutes: 120, label: '2 hours before' },
  { minutes: 30, label: '30 minutes before' },
];

@Component({
  selector: 'app-reminders',
  standalone: true,
  imports: [ReactiveFormsModule, MatButtonModule, MatIconModule, RouterLink],
  templateUrl: './reminders.component.html',
  styleUrl: './reminders.component.scss',
})
export class RemindersComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly fb = inject(FormBuilder);

  readonly offsetOptions = OFFSET_OPTIONS;
  readonly timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  readonly smsReady = signal(false);
  readonly emailReady = signal(false);
  readonly history = computed(() => this.reminders().filter(r => r.status !== 'PENDING').slice(0, 30));
  readonly clearingHistory = signal(false);
  readonly confirmClearHistory = signal(false);
  readonly historyMessage = signal<string | null>(null);
  readonly hasClearableHistory = computed(() => this.reminders().some(r => ['SENT', 'FAILED', 'CANCELLED'].includes(r.status)));

  clearHistory(): void {
    if (this.clearingHistory() || !this.confirmClearHistory()) return;
    this.clearingHistory.set(true);
    this.historyMessage.set(null);
    this.api.clearReminderHistory().subscribe({
      next: () => {
        this.reminders.update(list => list.filter(r => !['SENT', 'FAILED', 'CANCELLED'].includes(r.status)));
        this.clearingHistory.set(false);
        this.confirmClearHistory.set(false);
        this.historyMessage.set('Reminder history cleared.');
      },
      error: () => {
        this.clearingHistory.set(false);
        this.historyMessage.set('Could not clear history. Please try again.');
      },
    });
  }

  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  readonly saved = signal(false);

  readonly tasks = signal<TaskDto[]>([]);
  readonly events = signal<CalendarEventDto[]>([]);
  readonly reminders = signal<ReminderDto[]>([]);

  readonly scheduleForm = this.fb.nonNullable.group({
    entityKind: ['TASK' as 'CALENDAR_EVENT' | 'TASK', Validators.required],
    entityId: [''],
    emailEnabled: [false],
    smsEnabled: [false],
    offsets: this.fb.nonNullable.control<number[]>([1_440, 120], Validators.required),
  });

  readonly schedulableTasks = computed(() =>
    this.tasks().filter(
      (task) =>
        task.dueDate &&
        task.status !== 'COMPLETED' &&
        task.status !== 'CANCELLED',
    ),
  );

  readonly upcomingEvents = computed(() =>
    [...this.events()]
      .filter((event) => new Date(event.startAt).getTime() > Date.now())
      .sort((a, b) => new Date(a.startAt).getTime() - new Date(b.startAt).getTime()),
  );

  readonly pendingReminders = computed(() =>
    this.reminders()
      .filter((reminder) => reminder.status === 'PENDING')
      .sort((a, b) => new Date(a.reminderAt).getTime() - new Date(b.reminderAt).getTime()),
  );

  readonly reminderGroups = computed(() => this.groupReminders(this.pendingReminders()));

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.loading.set(true);
    this.error.set(null);
    this.saved.set(false);
    forkJoin({
      reminders: this.api.listReminders(),
      tasks: this.api.listTasks(),
      events: this.api.listCalendarEvents(),
      settings: this.api.getNotificationSettings(),
      phone: this.api.getPhoneStatus(),
    }).subscribe({
      next: ({ reminders, tasks, events, settings, phone }) => {
        this.smsReady.set(settings.smsEnabled && phone.phoneVerified);
        this.emailReady.set(settings.emailEnabled);
        this.scheduleForm.patchValue({ smsEnabled: this.smsReady(), emailEnabled: this.emailReady() });
        if (this.smsReady()) this.scheduleForm.controls.smsEnabled.enable();
        else this.scheduleForm.controls.smsEnabled.disable();
        if (this.emailReady()) this.scheduleForm.controls.emailEnabled.enable();
        else this.scheduleForm.controls.emailEnabled.disable();
        this.reminders.set(reminders);
        this.tasks.set(tasks);
        this.events.set(events);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load reminders.');
        this.loading.set(false);
      },
    });
  }

  readonly selectedIds = signal<string[]>([]);
  readonly selectedKind = signal<'TASK' | 'CALENDAR_EVENT'>('TASK');
  readonly scheduleMessage = signal<string | null>(null);
  readonly selectableItems = computed(() => this.selectedKind() === 'TASK' ? this.schedulableTasks() : this.upcomingEvents());

  readonly activeByEntity = computed(() => {
    const result = new Map<string, ReminderDto[]>();
    for (const reminder of this.reminders()) {
      if (reminder.status !== 'PENDING' && reminder.status !== 'PROCESSING') continue;
      const key = `${reminder.relatedEntityType}:${reminder.relatedEntityId}`;
      result.set(key, [...(result.get(key) ?? []), reminder]);
    }
    return result;
  });
  readonly selectedWithReminders = computed(() =>
    this.selectedIds().filter(id => this.existingReminders(id).length > 0).length);

  existingReminders(id: string): ReminderDto[] {
    return this.activeByEntity().get(`${this.selectedKind()}:${id}`) ?? [];
  }

  selectWithoutReminders(): void {
    this.selectedIds.set(this.selectableItems().filter(item => !this.existingReminders(item.id).length).map(item => item.id));
  }

  toggleItem(id: string): void {
    this.selectedIds.update(ids => ids.includes(id) ? ids.filter(item => item !== id) : [...ids, id]);
  }

  selectAll(): void { this.selectedIds.set(this.selectableItems().map(item => item.id)); }

  onEntityKindChange(): void {
    this.selectedKind.set(this.scheduleForm.controls.entityKind.value);
    this.selectedIds.set([]);
    this.scheduleForm.patchValue({ entityId: '' });
  }

  isOffsetSelected(minutes: number): boolean {
    return this.scheduleForm.controls.offsets.value.includes(minutes);
  }

  toggleOffset(minutes: number): void {
    const current = this.scheduleForm.controls.offsets.value;
    const next = current.includes(minutes)
      ? current.filter((value) => value !== minutes)
      : [...current, minutes].sort((a, b) => b - a);
    this.scheduleForm.controls.offsets.setValue(next);
    this.scheduleForm.controls.offsets.markAsTouched();
  }

  scheduleReminders(): void {
    if (this.saving()) return;
    const ids = this.selectedIds().filter(id => this.selectableItems().some(item => item.id === id));
    if (!ids.length) { this.error.set('Select at least one assignment or event.'); return; }
    if (this.scheduleForm.invalid) {
      this.scheduleForm.markAllAsTouched();
      return;
    }

    const value = this.scheduleForm.getRawValue();
    const channels: NotificationChannel[] = [];
    if (value.emailEnabled) {
      channels.push('EMAIL');
    }
    if (value.smsEnabled) {
      channels.push('SMS');
    }
    if (channels.length === 0) {
      this.error.set('Choose at least one channel: email or SMS.');
      return;
    }
    if (value.offsets.length === 0) {
      this.error.set('Choose at least one reminder time.');
      return;
    }

    this.saving.set(true);
    this.error.set(null);
    this.saved.set(false);

    this.scheduleMessage.set(null);
    from(ids).pipe(
      concatMap(id => this.api.scheduleReminders({
        relatedEntityType: value.entityKind, relatedEntityId: id,
        offsetMinutes: value.offsets, channels, timeZone: this.timeZone,
      }).pipe(
        map(() => ({ id, ok: true, message: '' })),
        catchError(err => of({ id, ok: false, message: err.error?.message ?? 'Could not schedule reminders.' })),
      )),
      toArray(),
    ).subscribe(results => {
      const failures = results.filter(result => !result.ok);
      const count = results.length - failures.length;
      this.selectedIds.set(failures.map(result => result.id));
      this.scheduleMessage.set(`Scheduled reminders for ${count} of ${results.length} selected items.`
        + (failures.length ? ` ${failures.length} could not be scheduled and remain selected. ${failures[0].message}` : ''));
      this.api.listReminders().subscribe({
        next: reminders => { this.reminders.set(reminders); this.saving.set(false); },
        error: () => { this.saving.set(false); this.error.set('Scheduling finished, but activity could not reload. Use Reload to check it.'); },
      });
    });
  }
  cancelReminder(reminder: ReminderDto): void {
    this.api.cancelReminder(reminder.id).subscribe({
      next: (updated) => {
        this.reminders.update((list) =>
          list.map((item) =>
            item.id === reminder.id
              ? { ...item, ...(updated ?? {}), status: updated?.status ?? 'CANCELLED' }
              : item,
          ),
        );
      },
      error: () => this.error.set('Could not cancel reminder.'),
    });
  }

  offsetLabel(reminder: ReminderDto): string {
    const eventAt = this.resolveEventAt(reminder);
    if (!eventAt) {
      return this.formatReminderAt(reminder.reminderAt);
    }
    const diffMinutes = Math.round((eventAt.getTime() - new Date(reminder.reminderAt).getTime()) / 60_000);
    const preset = OFFSET_OPTIONS.find((option) => option.minutes === diffMinutes);
    if (preset) {
      return preset.label;
    }
    if (diffMinutes >= 1_440 && diffMinutes % 1_440 === 0) {
      const days = diffMinutes / 1_440;
      return `${days} day${days === 1 ? '' : 's'} before`;
    }
    if (diffMinutes >= 60 && diffMinutes % 60 === 0) {
      const hours = diffMinutes / 60;
      return `${hours} hour${hours === 1 ? '' : 's'} before`;
    }
    return `${diffMinutes} minutes before`;
  }

  channelLabel(channel: NotificationChannel): string {
    return channel === 'EMAIL' ? 'Email' : 'SMS';
  }

  formatReminderAt(iso: string): string {
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) {
      return iso;
    }
    return date.toLocaleString(undefined, {
      weekday: 'short',
      month: 'short',
      day: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
    });
  }

  private groupReminders(reminders: ReminderDto[]): ReminderGroup[] {
    const groups = new Map<string, ReminderGroup>();
    for (const reminder of reminders) {
      const key = `${reminder.relatedEntityType}:${reminder.relatedEntityId}`;
      const title = this.entityTitle(reminder);
      const eventAt = this.resolveEventAt(reminder);
      const eventAtLabel = eventAt
        ? eventAt.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })
        : 'This item is no longer available in your calendar or tasks.';

      const existing = groups.get(key);
      if (existing) {
        existing.reminders.push(reminder);
      } else {
        groups.set(key, {
          key,
          title,
          eventAtLabel,
          reminders: [reminder],
        });
      }
    }
    return [...groups.values()].sort((a, b) => {
      const aTime = this.resolveEventAt(a.reminders[0])?.getTime() ?? 0;
      const bTime = this.resolveEventAt(b.reminders[0])?.getTime() ?? 0;
      return aTime - bTime;
    });
  }

  entityTitle(reminder: ReminderDto): string {
    if (reminder.relatedEntityType === 'TASK') {
      return this.tasks().find((task) => task.id === reminder.relatedEntityId)?.title ?? 'Unavailable task';
    }
    if (reminder.relatedEntityType === 'CALENDAR_EVENT') {
      return this.events().find((event) => event.id === reminder.relatedEntityId)?.title ?? 'Unavailable event';
    }
    return reminder.relatedEntityType;
  }

  private resolveEventAt(reminder: ReminderDto): Date | null {
    if (reminder.relatedEntityType === 'CALENDAR_EVENT') {
      const event = this.events().find((item) => item.id === reminder.relatedEntityId);
      return event ? new Date(event.startAt) : null;
    }
    if (reminder.relatedEntityType === 'TASK') {
      const task = this.tasks().find((item) => item.id === reminder.relatedEntityId);
      if (!task?.dueDate) {
        return null;
      }
      const time = task.dueTime ?? '09:00';
      return new Date(`${task.dueDate}T${time}`);
    }
    return null;
  }
}
