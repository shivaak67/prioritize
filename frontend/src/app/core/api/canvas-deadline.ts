import { CalendarEventDto, TaskDto } from './api.models';

/** Date-only Canvas deadlines retain their source date in every browser timezone. */
export function canvasDeadlineTask(event: CalendarEventDto): TaskDto {
  const date = new Date(event.startAt);
  const pad = (n: number) => String(n).padStart(2, '0');
  return {
    id: event.id, title: event.title, description: event.description,
    dueDate: event.allDay && event.canvasStartDate ? event.canvasStartDate
      : `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`,
    dueTime: event.allDay ? null : `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`,
    status: event.canvasCompleted ? 'COMPLETED' : 'TODO', priority: 'MEDIUM',
    categoryId: null, projectId: null, estimatedMinutes: null, actualMinutes: 0,
    completedAt: null, createdAt: event.createdAt, updatedAt: event.updatedAt,
  };
}
