import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { CanvasFeedStatus } from '../../core/api/api.models';

@Component({
  selector: 'app-canvas-connection',
  standalone: true,
  imports: [DatePipe, FormsModule, RouterLink, MatIconModule],
  templateUrl: './canvas-connection.component.html',
  styleUrl: './canvas-connection.component.scss',
})
export class CanvasConnectionComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly status = signal<CanvasFeedStatus | null>(null);
  readonly error = signal('');
  readonly message = signal('');
  readonly editing = signal(false);
  readonly confirmDisconnect = signal(false);
  readonly showFeedUrl = signal(false);
  feedUrl = '';
  timezone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  ngOnInit(): void {
    this.load();
  }
  load(): void {
    this.loading.set(true);
    this.error.set('');
    this.api
      .getCanvasFeed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (status) => {
          this.status.set(status);
          this.loading.set(false);
        },
        error: () => {
          this.error.set('Could not load the Canvas connection. Please retry.');
          this.loading.set(false);
        },
      });
  }
  connect(): void {
    if (this.busy()) return;
    if (!this.feedUrl.trim() || !this.timezone.trim()) {
      this.error.set('Enter your Canvas Calendar Feed link and timezone.');
      return;
    }
    this.run(
      this.api.connectCanvasFeed(this.feedUrl.trim(), this.timezone.trim()),
      'Canvas is connected. Your calendar is ready.',
    );
  }
  sync(): void {
    if (!this.busy())
      this.run(this.api.syncCanvasFeed(), 'Canvas calendar updated.');
  }
  private run(request: Observable<CanvasFeedStatus>, message: string): void {
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (status) => {
        this.status.set(status);
        this.busy.set(false);
        if (!status.error) {
          this.feedUrl = '';
          this.showFeedUrl.set(false);
          this.editing.set(false);
          this.message.set(message);
        }
      },
      error: (response) => {
        this.busy.set(false);
        this.error.set(
          response?.error?.message ||
            'Could not connect to Canvas. Please check the feed link and retry.',
        );
      },
    });
  }
  disconnect(): void {
    if (this.busy() || !this.confirmDisconnect()) return;
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    this.api
      .disconnectCanvasFeed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.confirmDisconnect.set(false);
          this.feedUrl = '';
          this.showFeedUrl.set(false);
          this.editing.set(false);
          this.message.set(
            'Canvas disconnected. Your personal plans are unchanged.',
          );
          this.load();
        },
        error: () => {
          this.busy.set(false);
          this.error.set('Could not disconnect Canvas. Please retry.');
        },
      });
  }
}
