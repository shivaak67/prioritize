import { Component, OnInit, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';

@Component({
  selector: 'app-auth-callback',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './auth-callback.component.html',
  styleUrl: './auth-callback.component.scss',
})
export class AuthCallbackComponent implements OnInit {
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);

  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.auth.refreshSession().subscribe({
      next: () => {
        void this.router.navigateByUrl('/dashboard');
      },
      error: () => {
        this.auth.logout();
        this.error.set(
          'Google sign-in succeeded, but we could not load your profile.',
        );
      },
    });
  }
}
