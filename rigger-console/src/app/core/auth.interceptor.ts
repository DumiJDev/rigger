import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * Attaches the bearer token and turns an expired session into a clean redirect.
 *
 * <p>A 401 always means "log in again" here: the backend issues short-lived JWTs and exposes no
 * refresh endpoint, so there is nothing to retry. The login call itself is exempt, otherwise a
 * wrong password would clear the session and bounce the user mid-form.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  const isLogin = req.url.includes('/api/v1/auth/login');
  const token = auth.token;

  const authed =
    token && !isLogin
      ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
      : req;

  return next(authed).pipe(
    catchError((err: HttpErrorResponse) => {
      if (err.status === 401 && !isLogin) {
        auth.clear();
        void router.navigate(['/login'], { queryParams: { expired: '1' } });
      }
      return throwError(() => err);
    }),
  );
};
