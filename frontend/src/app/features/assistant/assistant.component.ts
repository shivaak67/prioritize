import {
  Component,
  ElementRef,
  OnDestroy,
  OnInit,
  ViewChild,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { Subscription, interval, switchMap, takeWhile } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { AssistantMessage } from '../../core/api/api.models';

interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

const STARTER_PROMPTS = [
  'What should I focus on today?',
  'Create a task due today at 4 PM',
  'What tasks are overdue?',
  "What's on my calendar this week?",
];

const LOADING_STAGES = [
  { afterMs: 0, message: 'Thinking…' },
  { afterMs: 2500, message: 'Loading your tasks and schedule…' },
  { afterMs: 8000, message: 'Still working on your request…' },
  { afterMs: 30000, message: 'This is taking longer than usual. Please wait for the result before sending again.' },
  { afterMs: 60000, message: 'Still waiting for a response…' },
];

@Component({
  selector: 'app-assistant',
  standalone: true,
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule],
  templateUrl: './assistant.component.html',
  styleUrl: './assistant.component.scss',
})
export class AssistantComponent implements OnInit, OnDestroy {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);

  @ViewChild('scrollAnchor') private scrollAnchor?: ElementRef<HTMLDivElement>;

  private statusPoll?: Subscription;
  private loadingTimers: ReturnType<typeof setTimeout>[] = [];

  readonly loading = signal(false);
  readonly loadingMessage = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  readonly configured = signal(true);
  readonly modelReady = signal(true);
  readonly modelWarming = signal(false);
  readonly warmupMessage = signal<string | null>(null);
  readonly input = signal('');
  readonly messages = signal<ChatMessage[]>([
    {
      role: 'assistant',
      content:
        'Hi! I can answer questions about your tasks and schedule, and I can also create or update tasks and calendar events for you. Try asking me to add a task or event.',
    },
  ]);

  readonly starterPrompts = STARTER_PROMPTS;

  ngOnInit(): void {
    this.refreshStatus();

    const presetQuestion = this.route.snapshot.queryParamMap.get('q')?.trim();
    if (presetQuestion) {
      this.usePrompt(presetQuestion);
    }
    this.statusPoll = interval(3000)
      .pipe(
        switchMap(() => this.api.getAssistantStatus()),
        takeWhile((status) => status.configured && !status.ready, true),
      )
      .subscribe({
        next: (status) => this.applyStatus(status),
        error: () => {
          this.modelReady.set(true);
          this.modelWarming.set(false);
        },
      });
  }

  ngOnDestroy(): void {
    this.statusPoll?.unsubscribe();
    this.clearLoadingTimers();
  }

  send(): void {
    const text = this.input().trim();
    if (!text || this.loading()) {
      return;
    }

    this.input.set('');
    this.error.set(null);
    this.appendMessage('user', text);
    this.requestReply(text);
  }

  usePrompt(prompt: string): void {
    if (this.loading()) {
      return;
    }
    this.input.set(prompt);
    this.send();
  }

  clearChat(): void {
    if (this.loading()) {
      return;
    }
    this.messages.set([
      {
        role: 'assistant',
        content: 'Chat cleared. What would you like to know about your tasks or schedule?',
      },
    ]);
    this.error.set(null);
  }

  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.send();
    }
  }

  private refreshStatus(): void {
    this.api.getAssistantStatus().subscribe({
      next: (status) => this.applyStatus(status),
      error: () => {
        this.modelReady.set(true);
        this.modelWarming.set(false);
      },
    });
  }

  private applyStatus(status: {
    configured: boolean;
    ready: boolean;
    warming: boolean;
    message: string;
  }): void {
    this.configured.set(status.configured);
    this.modelReady.set(!status.configured || status.ready);
    this.modelWarming.set(status.configured && status.warming);
    this.warmupMessage.set(
      status.configured && !status.ready ? status.message : null,
    );
  }

  private requestReply(latestUserMessage: string): void {
    this.loading.set(true);
    this.startLoadingMessages();

    const history: AssistantMessage[] = this.messages()
      .filter((m) => m.content)
      .slice(0, -1)
      .map((m) => ({ role: m.role, content: m.content }));

    this.api.chatWithAssistant({ message: latestUserMessage, history, timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone }).subscribe({
      next: (response) => {
        this.configured.set(response.enabled);
        this.appendMessage('assistant', response.reply);
        this.stopLoading();
        this.refreshStatus();
      },
      error: (err) => {
        const statusCode = err?.status as number | undefined;
        const serverMessage = err?.error?.message as string | undefined;
        if (statusCode === 401) {
          this.error.set('Your session expired. Redirecting to login…');
        } else if (statusCode === 0) {
          this.error.set('Connection interrupted. Check your connection and try again. If you requested a change, check your tasks or calendar before repeating it.');
        } else if (statusCode === 502) {
          this.error.set(
            serverMessage ?? 'The AI service could not finish this request. Please try again. If you requested a change, check your tasks or calendar before repeating it.',
          );
        } else if (serverMessage) {
          this.error.set(serverMessage);
        } else {
          this.error.set('Could not get a response. Please try again. If you requested a change, check your tasks or calendar before repeating it.');
        }
        this.stopLoading();
      },
    });
  }

  private startLoadingMessages(): void {
    this.clearLoadingTimers();
    for (const stage of LOADING_STAGES) {
      const timer = setTimeout(() => {
        if (this.loading()) {
          this.loadingMessage.set(stage.message);
        }
      }, stage.afterMs);
      this.loadingTimers.push(timer);
    }
  }

  private stopLoading(): void {
    this.loading.set(false);
    this.loadingMessage.set(null);
    this.clearLoadingTimers();
  }

  private clearLoadingTimers(): void {
    for (const timer of this.loadingTimers) {
      clearTimeout(timer);
    }
    this.loadingTimers = [];
  }

  private appendMessage(role: 'user' | 'assistant', content: string): void {
    this.messages.update((items) => [...items, { role, content }]);
    queueMicrotask(() => this.scrollToBottom());
  }

  private scrollToBottom(): void {
    this.scrollAnchor?.nativeElement.scrollIntoView({ behavior: 'smooth' });
  }
}
