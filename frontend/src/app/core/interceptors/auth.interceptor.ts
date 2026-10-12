import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { environment } from '../../../environments/environment';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const token = auth.getToken();

  const isPublicAuth =
    req.url.includes('/api/auth/login') ||
    req.url.includes('/api/auth/register') ||
    req.url.includes('/api/auth/refresh') ||
    req.url.includes('/api/auth/logout');

  let authReq =
    token && !isPublicAuth
      ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
      : req;
  if (
    req.url.startsWith(environment.apiBaseUrl) ||
    req.url.startsWith('/api/')
  ) {
    authReq = authReq.clone({ withCredentials: true });
  }

  return next(authReq).pipe(
    catchError((err) => {
      const onCallback = router.url.startsWith('/auth/callback');
      if (err.status === 401 && !isPublicAuth && !onCallback) {
        return auth.refreshSession().pipe(
          switchMap((session) =>
            next(
              req.clone({
                withCredentials: true,
                setHeaders: { Authorization: `Bearer ${session.accessToken}` },
              }),
            ),
          ),
          catchError((refreshError) => {
            auth.clearSession();
            void router.navigateByUrl('/auth/login');
            return throwError(() => refreshError);
          }),
        );
      }
      return throwError(() => err);
    }),
  );
};
