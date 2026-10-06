import { of } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { Router } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { TaskDto, CalendarEventDto } from '../../core/api/api.models';
import { DashboardComponent } from './dashboard.component';

describe('Dashboard deadlines', () => {
  let component: DashboardComponent;
  const task = (id: string, dueDate: string, status = 'TODO', dueTime: string | null = null) =>
    ({ id, title: id, dueDate, status, dueTime } as TaskDto);
  const event = (id: string, startAt: string, endAt: string) =>
    ({ id, title: id, startAt, endAt, allDay: false } as CalendarEventDto);
  beforeEach(() => {
    jasmine.clock().install();
    jasmine.clock().mockDate(new Date(2026, 8, 5, 10));
    TestBed.configureTestingModule({ providers: [
      { provide: ApiService, useValue: {} },
      { provide: AuthService, useValue: { currentUser: signal(null) } },
      { provide: Router, useValue: { navigate: jasmine.createSpy('navigate') } },
    ] });
    component = TestBed.runInInjectionContext(() => new DashboardComponent());
  });
  afterEach(() => jasmine.clock().uninstall());
  it('shows older overdue work and next-week tasks while excluding completed work', () => {
    component.tasks.set([task('old', '2026-08-01'), task('next', '2026-09-08'), task('done', '2026-09-02', 'COMPLETED'), task('far', '2026-09-20')]);
    expect(component.upcomingDeadlines().map(t => t.id)).toEqual(['task-old', 'task-next']);
    expect(component.upcomingDeadlines()[0].dateLabel).toBe('Overdue');
  });
  it('excludes ended events but retains ongoing events and sorts timestamps consistently', () => {
    component.events.set([
      event('ended', '2026-09-03T10:00:00', '2026-09-03T11:00:00'),
      event('ongoing', '2026-09-04T10:00:00', '2026-09-06T11:00:00'),
      event('future', '2026-09-08T10:00:00', '2026-09-08T11:00:00'),
    ]);
    expect(component.upcomingDeadlines().map(t => t.id)).toEqual(['event-ongoing', 'event-future']);
  });
  it('includes tasks due today without a specific time', () => {
    component.tasks.set([task('anytime', '2026-09-05'), task('timed', '2026-09-05', 'TODO', '09:00')]);
    expect(component.todayPlan().map(t => t.id)).toEqual(['task-timed', 'task-anytime']);
    expect(component.todayPlan()[1].time).toBe('Any time');
  });
  it('counts a passed due time today as overdue consistently with the deadline list', () => {
    component.tasks.set([task('late', '2026-09-05', 'TODO', '09:00'), task('later', '2026-09-05', 'TODO', '11:00')]);
    expect(component.stats().find(stat => stat.label === 'Overdue')?.value).toBe(1);
    expect(component.upcomingDeadlines().filter(item => item.overdue).length).toBe(1);
  });
  it('prioritizes overdue work before today and later work', () => {
    component.tasks.set([
      { ...task('today-high', '2026-09-05', 'TODO', '14:00'), priority: 'URGENT' } as TaskDto,
      { ...task('overdue-low', '2026-09-04', 'TODO', '14:00'), priority: 'LOW' } as TaskDto,
      { ...task('later', '2026-09-08', 'TODO', '14:00'), priority: 'HIGH' } as TaskDto,
    ]);
    expect(component.nextItems().map(item => item.id)).toEqual(['overdue-low', 'today-high', 'later']);
    expect(component.currentNext()?.badge).toBe('Overdue');
  });
  it('marks a Canvas next item complete locally and removes it from the priority queue', () => {
    const api = TestBed.inject(ApiService);
    api.setCanvasAssignmentCompleted = jasmine.createSpy().and.returnValue(of(void 0));
    const quiz = {
      ...event('quiz-complete', '2026-09-05T14:00:00', '2026-09-05T14:00:00.001'),
      canvasKind: 'DEADLINE', canvasCompleted: false,
    } as CalendarEventDto;
    component.events.set([quiz]);

    component.completeNext();

    expect(api.setCanvasAssignmentCompleted).toHaveBeenCalledWith('quiz-complete', true);
    expect(component.events()[0].canvasCompleted).toBeTrue();
    expect(component.nextItems()).toEqual([]);
  });
  it('does not count cancelled tasks against weekly completion', () => {
    component.tasks.set([task('cancelled', '2026-09-02', 'CANCELLED'), task('done', '2026-09-02', 'COMPLETED')]);
    expect(component.weeklyProgress()).toEqual({ done: 1, total: 1, percent: 100 });
  });
  it('discovers Canvas for unconnected users and hides setup after connection', () => {
    const api = TestBed.inject(ApiService);
    api.getCanvasFeed = jasmine.createSpy().and.returnValue(of({ connected: false }));
    spyOn(component, 'reload'); component.ngOnInit(); expect(component.canvasConnected()).toBeFalse();
    api.getCanvasFeed = jasmine.createSpy().and.returnValue(of({ connected: true }));
    component.ngOnInit(); expect(component.canvasConnected()).toBeTrue();
  });it('includes a date-only Canvas quiz in today, counts, progress, and deadlines', () => {
    const quiz = { ...event('quiz', '2026-09-06T00:00:00Z', '2026-09-07T00:00:00Z'),
      canvasKind: 'DEADLINE', allDay: true, canvasStartDate: '2026-09-05', canvasCompleted: false } as CalendarEventDto;
    component.tasks.set([task('done', '2026-09-05', 'COMPLETED')]);
    component.events.set([quiz]);
    expect(component.stats().map(s => s.value)).toEqual([1, 0, 1]);
    expect(component.weeklyProgress()).toEqual({ done: 1, total: 2, percent: 50 });
    expect(component.todayPlan().map(i => i.id)).toEqual(['event-quiz']);
    expect(component.upcomingDeadlines().map(i => i.id)).toEqual(['event-quiz']);
    component.events.set([{ ...quiz, canvasCompleted: true }]);
    expect(component.stats().map(s => s.value)).toEqual([0, 0, 0]);
    expect(component.weeklyProgress().percent).toBe(100);
    expect(component.todayPlan()).toEqual([]);
    expect(component.upcomingDeadlines()).toEqual([]);
  });
  it('retains past Canvas deadlines as overdue but excludes ordinary events from counts', () => {
    component.events.set([
      { ...event('old', '2026-08-01T12:00:00', '2026-08-01T12:00:00.001'), canvasKind: 'DEADLINE' },
      { ...event('late', '2026-09-05T09:00:00', '2026-09-05T09:00:00.001'), canvasKind: 'DEADLINE' },
      event('meeting', '2026-09-05T12:00:00', '2026-09-05T13:00:00'),
    ] as CalendarEventDto[]);
    expect(component.stats().map(s => s.value)).toEqual([1, 2, 1]);
    expect(component.upcomingDeadlines().filter(i => i.overdue).map(i => i.id)).toEqual(['event-old', 'event-late']);
  });
});
