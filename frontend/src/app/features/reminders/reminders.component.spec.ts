import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { CalendarEventDto, ReminderDto, TaskDto } from '../../core/api/api.models';
import { RemindersComponent } from './reminders.component';

describe('Reminder selection and history', () => {
  let component: RemindersComponent;
  let api: jasmine.SpyObj<ApiService>;
  beforeEach(() => {
    api = jasmine.createSpyObj('ApiService', ['scheduleReminders', 'listReminders', 'clearReminderHistory', 'cancelReminder', 'listTasks', 'listCalendarEvents', 'getNotificationSettings', 'getPhoneStatus']);
    api.listReminders.and.returnValue(of([]));
    TestBed.configureTestingModule({ providers: [{ provide: ApiService, useValue: api }] });
    component = TestBed.runInInjectionContext(() => new RemindersComponent());
    component.tasks.set(['one', 'two'].map(id => ({ id, title: id, status: 'TODO', dueDate: '2099-01-01' } as TaskDto)));
    component.scheduleForm.patchValue({ emailEnabled: true });
  });
  it('selects all, toggles individuals and clears selection on type changes', () => {
    component.selectAll(); expect(component.selectedIds()).toEqual(['one', 'two']);
    component.toggleItem('one'); expect(component.selectedIds()).toEqual(['two']);
    component.scheduleForm.patchValue({ entityKind: 'CALENDAR_EVENT' }); component.onEntityKindChange();
    expect(component.selectedIds()).toEqual([]);
  });
  it('schedules each selection and keeps failed items selected without retrying successful ones', () => {
    api.scheduleReminders.and.callFake(request => request.relatedEntityId === 'one'
      ? of({ reminders: [] }) : throwError(() => ({ error: { message: 'Too late' } })));
    component.selectAll(); component.scheduleReminders();
    expect(api.scheduleReminders.calls.count()).toBe(2);
    expect(component.selectedIds()).toEqual(['two']);
    expect(component.scheduleMessage()).toContain('1 of 2'); expect(component.scheduleMessage()).toContain('Too late');
    expect(component.saving()).toBeFalse();
  });
  it('requires selection and confirmation, preserves pending and processing activity', () => {
    component.scheduleReminders(); expect(api.scheduleReminders).not.toHaveBeenCalled();
    component.clearHistory(); expect(api.clearReminderHistory).not.toHaveBeenCalled();
    component.reminders.set(['PENDING', 'PROCESSING', 'SENT', 'FAILED', 'CANCELLED'].map(status => ({ status } as ReminderDto)));
    component.confirmClearHistory.set(true); api.clearReminderHistory.and.returnValue(of(undefined)); component.clearHistory();
    expect(component.reminders().map(r => r.status)).toEqual(['PENDING', 'PROCESSING']);
  });
  it('keeps history visible on failure', () => {
    component.reminders.set([{ status: 'SENT' } as ReminderDto]); component.confirmClearHistory.set(true);
    api.clearReminderHistory.and.returnValue(throwError(() => new Error('offline'))); component.clearHistory();
    expect(component.history().length).toBe(1); expect(component.clearingHistory()).toBeFalse();
  });
  it('marks only active reminders for the matching type and excludes them from new selection', () => {
    component.reminders.set([
      { id: 'a', relatedEntityType: 'TASK', relatedEntityId: 'one', status: 'PENDING' },
      { id: 'b', relatedEntityType: 'TASK', relatedEntityId: 'one', status: 'PROCESSING' },
      { id: 'c', relatedEntityType: 'TASK', relatedEntityId: 'two', status: 'SENT' },
      { id: 'd', relatedEntityType: 'CALENDAR_EVENT', relatedEntityId: 'two', status: 'PENDING' },
    ] as ReminderDto[]);
    component.selectWithoutReminders();
    expect(component.selectedIds()).toEqual(['two']);
    expect(component.existingReminders('one').length).toBe(2);
    component.selectAll(); expect(component.selectedWithReminders()).toBe(1);
    component.scheduleForm.patchValue({ entityKind: 'CALENDAR_EVENT' }); component.onEntityKindChange();
    expect(component.existingReminders('one')).toEqual([]);
    expect(component.existingReminders('two').length).toBe(1);
  });
  it('updates the marker after scheduling and cancelling without reloading the page', () => {
    const reminder = { id: 'a', relatedEntityType: 'TASK', relatedEntityId: 'one', status: 'PENDING' } as ReminderDto;
    api.scheduleReminders.and.returnValue(of({ reminders: [reminder] }));
    api.listReminders.and.returnValue(of([reminder]));
    component.toggleItem('one'); component.scheduleReminders();
    expect(component.existingReminders('one').length).toBe(1);
    api.cancelReminder.and.returnValue(of({ ...reminder, status: 'CANCELLED' }));
    component.cancelReminder(reminder);
    expect(component.existingReminders('one')).toEqual([]);
  });
  it('loads calendar titles beyond a short date window and identifies unavailable items honestly', () => {
    const event = { id: 'event', title: 'Future exam', startAt: '2099-12-20T09:00:00Z' } as CalendarEventDto;
    api.listTasks.and.returnValue(of([])); api.listCalendarEvents.and.returnValue(of([event]));
    api.getNotificationSettings.and.returnValue(of({ emailEnabled: true, smsEnabled: false } as any));
    api.getPhoneStatus.and.returnValue(of({ phoneVerified: false } as any));
    api.listReminders.and.returnValue(of([{ id: 'r', relatedEntityType: 'CALENDAR_EVENT', relatedEntityId: 'event', status: 'PENDING', reminderAt: '2099-12-19T09:00:00Z' } as ReminderDto]));
    component.reload();
    expect(api.listCalendarEvents).toHaveBeenCalledWith();
    expect(component.reminderGroups()[0].title).toBe('Future exam');
    expect(component.entityTitle({ relatedEntityType: 'CALENDAR_EVENT', relatedEntityId: 'missing' } as ReminderDto)).toBe('Unavailable event');
  });

});
