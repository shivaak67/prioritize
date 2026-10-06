import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { AssistantComponent } from './assistant.component';

describe('Assistant conversation controls', () => {
  let component: AssistantComponent;
  let api: jasmine.SpyObj<ApiService>;

  beforeEach(() => {
    api = jasmine.createSpyObj('ApiService', [
      'chatWithAssistant',
      'getAssistantStatus',
    ]);
    api.getAssistantStatus.and.returnValue(
      of({ configured: false, ready: true, warming: false, message: '' }),
    );
    TestBed.configureTestingModule({
      providers: [
        { provide: ApiService, useValue: api },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: { get: () => null } } },
        },
      ],
    });
    component = TestBed.runInInjectionContext(() => new AssistantComponent());
  });
  afterEach(() => component.ngOnDestroy());

  it('does not send a keyboard submission while the model is warming', () => {
    component.modelReady.set(false);
    component.input.set('What is due today?');
    component.onKeydown(
      new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }),
    );
    expect(api.chatWithAssistant).not.toHaveBeenCalled();
    expect(component.input()).toBe('What is due today?');
  });

  it('submits a suggestion only once while a reply is pending', () => {
    api.chatWithAssistant.and.returnValue(new Subject());
    component.usePrompt('What tasks are overdue?');
    component.usePrompt('What tasks are overdue?');
    expect(api.chatWithAssistant).toHaveBeenCalledTimes(1);
    expect(component.messages().at(-1)?.role).toBe('user');
    expect(component.loading()).toBeTrue();
  });

  it('retains conversation context and exposes retry guidance after a failed request', () => {
    api.chatWithAssistant.and.returnValue(throwError(() => ({ status: 0 })));
    component.usePrompt('What is due today?');
    expect(component.messages().at(-1)?.content).toBe('What is due today?');
    expect(component.error()).toContain('Connection interrupted');
    expect(component.loading()).toBeFalse();
    component.clearChat();
    expect(component.messages().length).toBe(1);
    expect(component.error()).toBeNull();
  });
});
