import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, finalize, shareReplay, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  AuthConfig,
  AuthResponse,
  LoginRequest,
  RegisterRequest,
  UserDto,
} from './auth.models';

const TOKEN_KEY = 'prioritize_access_token';
const USER_KEY = 'prioritize_user';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly api = `${environment.apiBaseUrl}/api/auth`;
  private refreshRequest$: Observable<AuthResponse> | null = null;

  readonly currentUser = signal<UserDto | null>(this.readStoredUser());

  isAuthenticated(): boolean {
    return !!this.getToken();
  }

  getToken(): string | null {
    return localStorage.getItem(TOKEN_KEY);
  }

  login(body: LoginRequest): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>(`${this.api}/login`, body)
      .pipe(tap((res) => this.persist(res.accessToken, res.user)));
  }

  register(body: RegisterRequest): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>(`${this.api}/register`, body)
      .pipe(tap((res) => this.persist(res.accessToken, res.user)));
  }

  refreshSession(): Observable<AuthResponse> {
    if (!this.refreshRequest$) {
      this.refreshRequest$ = this.http
        .post<AuthResponse>(
          `${this.api}/refresh`,
          {},
          { withCredentials: true },
        )
        .pipe(
          tap((res) => this.persist(res.accessToken, res.user)),
          finalize(() => (this.refreshRequest$ = null)),
          shareReplay({ bufferSize: 1, refCount: false }),
        );
    }
    return this.refreshRequest$;
  }

  getConfig(): Observable<AuthConfig> {
    return this.http.get<AuthConfig>(`${this.api}/config`);
  }

  me(): Observable<UserDto> {
    return this.http.get<UserDto>(`${this.api}/me`).pipe(
      tap((user) => {
        localStorage.setItem(USER_KEY, JSON.stringify(user));
        this.currentUser.set(user);
      }),
    );
  }

  refreshCurrentUser(): void {
    this.me().subscribe();
  }

  startGoogleSignIn(): void {
    window.location.href = `${environment.apiBaseUrl}/oauth2/authorization/google`;
  }

  completeOAuthLogin(token: string): Observable<UserDto> {
    localStorage.setItem(TOKEN_KEY, token);
    return this.me();
  }

  setSession(token: string, user: UserDto): void {
    this.persist(token, user);
  }

  userFromAccessToken(token: string): UserDto | null {
    try {
      const payloadPart = token.split('.')[1];
      if (!payloadPart) {
        return null;
      }
      const normalized = payloadPart.replace(/-/g, '+').replace(/_/g, '/');
      const payload = JSON.parse(atob(normalized)) as {
        sub?: string;
        email?: string;
      };
      if (!payload.sub || !payload.email) {
        return null;
      }
      return {
        id: payload.sub,
        email: payload.email,
        firstName: 'Google',
        lastName: 'User',
        authProvider: 'GOOGLE',
        phoneNumber: null,
        phoneVerified: false,
      };
    } catch {
      return null;
    }
  }

  logout(): void {
    this.http
      .post<void>(`${this.api}/logout`, {}, { withCredentials: true })
      .subscribe({ error: () => undefined });
    this.clearSession();
  }

  clearSession(): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    this.currentUser.set(null);
  }

  private persist(token: string, user: UserDto): void {
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(USER_KEY, JSON.stringify(user));
    this.currentUser.set(user);
  }

  private readStoredUser(): UserDto | null {
    const raw = localStorage.getItem(USER_KEY);
    if (!raw) {
      return null;
    }
    try {
      return JSON.parse(raw) as UserDto;
    } catch {
      return null;
    }
  }
}
