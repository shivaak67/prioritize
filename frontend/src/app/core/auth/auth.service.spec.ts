import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../environments/environment';
import { AuthResponse } from './auth.models';
import { AuthService } from './auth.service';

describe('AuthService refresh sessions', () => {
  let auth: AuthService;
  let http: HttpTestingController;

  const response: AuthResponse = {
    accessToken: 'new-access-token',
    expiresIn: 900000,
    user: {
      id: '3ea93830-f4a4-4de4-b7d8-27a509f63565',
      firstName: 'Remembered',
      lastName: 'User',
      email: 'remembered@example.com',
      authProvider: 'GOOGLE',
      phoneNumber: null,
      phoneVerified: false,
    },
  };

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  it('deduplicates concurrent refreshes and persists the renewed session', () => {
    auth.refreshSession().subscribe();
    auth.refreshSession().subscribe();

    const request = http.expectOne(
      `${environment.apiBaseUrl}/api/auth/refresh`,
    );
    expect(request.request.withCredentials).toBeTrue();
    request.flush(response);

    expect(auth.getToken()).toBe('new-access-token');
    expect(auth.currentUser()?.email).toBe('remembered@example.com');
  });

  it('revokes the server session and clears local state on logout', () => {
    auth.setSession(response.accessToken, response.user);
    auth.logout();

    const request = http.expectOne(`${environment.apiBaseUrl}/api/auth/logout`);
    expect(request.request.withCredentials).toBeTrue();
    request.flush(null);

    expect(auth.getToken()).toBeNull();
    expect(auth.currentUser()).toBeNull();
  });
});
