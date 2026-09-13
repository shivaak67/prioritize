import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { TaskDto, CalendarEventDto } from '../../core/api/api.models';
import { TasksComponent } from './tasks.component';

describe('Task discovery', () => {
  let component: TasksComponent;
  let api: jasmine.SpyObj<ApiService>;
  const task = (id: string, title: string, status = 'TODO', dueDate: string | null = null, priority = 'LOW') =>
    ({ id, title, status, dueDate, priority, description: null, dueTime: null } as TaskDto);
  beforeEach(() => {
    api = jasmine.createSpyObj('ApiService', ['listTasks', 'listCalendarEvents', 'createTask', 'updateCalendarEvent', 'deleteCalendarEvent', 'setCanvasAssignmentCompleted']);
    api.listTasks.and.returnValue(of([]));
    api.listCalendarEvents.and.returnValue(of([]));
    TestBed.configureTestingModule({ providers: [
      { provide: ApiService, useValue: api },
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({ view: 'overdue' }) } } },
    ] });
    component = TestBed.runInInjectionContext(() => new TasksComponent());
  });
  it('defaults to open work and sorts dated tasks before undated tasks', () => {
    component.tasks.set([task('1', 'Later'), task('2', 'Done', 'COMPLETED'), task('3', 'Due', 'TODO', '2026-09-05')]);
    expect(component.visibleTasks().map(t => t.id)).toEqual(['3', '1']);
  });
  it('combines case-insensitive search with status and priority sorting', () => {
    component.tasks.set([task('1', 'Read notes'), task('2', 'READ book', 'TODO', null, 'URGENT'), task('3', 'Read old', 'COMPLETED')]);
    component.search.set(' READ ');
    component.sort.set('priority');
    expect(component.visibleTasks().map(t => t.id)).toEqual(['2', '1']);
    component.view.set('completed');
    expect(component.visibleTasks().map(t => t.id)).toEqual(['3']);
  });
  it('honors dashboard links and excludes completed work from overdue', () => {
    component.ngOnInit();
    component.tasks.set([task('1', 'Late', 'TODO', '2000-01-01'), task('2', 'Done', 'COMPLETED', '2000-01-01')]);
    expect(component.visibleTasks().map(t => t.id)).toEqual(['1']);
    component.clearFilters();
    expect(component.visibleTasks().length).toBe(2);
  });
  it('rejects whitespace-only titles without making a request', () => {
    component.form.patchValue({ title: '   ' });
    component.submit();
    expect(api.createTask).not.toHaveBeenCalled();
    expect(component.form.controls.title.touched).toBeTrue();
    expect(component.form.controls.title.invalid).toBeTrue();
  });
  const event = (kind: 'DEADLINE' | 'EVENT' | null = null) => ({ id: 'event', title: 'Essay', canvasKind: kind,
    startAt: '2026-09-15T18:00:00Z', endAt: '2026-09-15T19:00:00Z', allDay: false,
    categoryId: 'category', description: 'Keep notes' } as CalendarEventDto);
  it('keeps imported assignments and events out of time blocks and preserves date-only deadlines', () => {
    component.events.set([event(), { ...event('DEADLINE'), id: 'due' }, { ...event('EVENT'), id: 'meeting' }]);
    expect(component.upcomingEvents().map(e => e.id)).toEqual(['event']);
    expect(component.canvasAssignments().map(e => e.id)).toEqual(['due']);
    expect(component.canvasEvents().map(e => e.id)).toEqual(['meeting']);
    expect(component.formatEventWhen({ ...event('DEADLINE'), allDay: true, canvasStartDate: '2026-09-16' })).toBe('2026-09-16 · All day');
    expect(component.formatEventWhen(event('DEADLINE'))).not.toContain('–');
  });
  it('edits blocks while preserving metadata, and rejects invalid ranges', () => {
    const block = event(); component.events.set([block]); component.startEventEdit(block);
    component.eventEditForm.patchValue({ title: ' Revised ', startLocal: '2026-09-15T10:00', endLocal: '2026-09-15T09:00' });
    component.saveEventEdit(block); expect(api.updateCalendarEvent).not.toHaveBeenCalled();
    component.eventEditForm.patchValue({ endLocal: '2026-09-15T11:00' });
    api.updateCalendarEvent.and.returnValue(of({ ...block, title: 'Revised' }));
    component.saveEventEdit(block);
    expect(api.updateCalendarEvent).toHaveBeenCalledWith('event', jasmine.objectContaining({ title: 'Revised', categoryId: 'category', description: 'Keep notes' }));
    expect(component.events()[0].title).toBe('Revised'); expect(component.editingEventId()).toBeNull();
  });
  it('preserves failed edits and prevents imported record mutations', () => {
    component.startEventEdit(event()); api.updateCalendarEvent.and.returnValue(throwError(() => new Error('offline')));
    component.saveEventEdit(event()); expect(component.editingEventId()).toBe('event'); expect(component.eventEditError()).toBeTruthy();
    api.updateCalendarEvent.calls.reset(); component.saveEventEdit(event('DEADLINE')); component.removeEvent(event('DEADLINE'));
    expect(api.updateCalendarEvent).not.toHaveBeenCalled(); expect(api.deleteCalendarEvent).not.toHaveBeenCalled();
    component.cancelEventEdit(); expect(component.editingEventId()).toBeNull();
  });  it('completes and reopens Canvas assignments while preserving status on failure', () => {
    const assignment = event('DEADLINE'); component.events.set([assignment]);
    api.setCanvasAssignmentCompleted.and.returnValue(of(undefined));
    component.toggleCanvasComplete(assignment); expect(component.events()[0].canvasCompleted).toBeTrue();
    component.toggleCanvasComplete(component.events()[0]); expect(component.events()[0].canvasCompleted).toBeFalse();
    api.setCanvasAssignmentCompleted.and.returnValue(throwError(() => new Error('offline')));
    component.toggleCanvasComplete(component.events()[0]); expect(component.events()[0].canvasCompleted).toBeFalse();
    expect(component.error()).toBeTruthy(); expect(component.completingCanvasId()).toBeNull();
  });  it('filters Canvas assignments when opening a dashboard count', () => {
    jasmine.clock().install();
    try {
      jasmine.clock().mockDate(new Date(2026, 8, 13, 12));
      component.events.set([
        { ...event('DEADLINE'), id: 'quiz', allDay: true, canvasStartDate: '2026-09-13' },
        { ...event('DEADLINE'), id: 'done', allDay: true, canvasStartDate: '2026-09-13', canvasCompleted: true },
        { ...event('DEADLINE'), id: 'old', allDay: true, canvasStartDate: '2026-08-01' },
      ]);
      component.view.set('today'); expect(component.canvasAssignments().map(e => e.id)).toEqual(['quiz']);
      component.view.set('overdue'); expect(component.canvasAssignments().map(e => e.id)).toEqual(['old']);
      component.view.set('completed'); expect(component.canvasAssignments().map(e => e.id)).toEqual(['done']);
    } finally { jasmine.clock().uninstall(); }
  });
});
