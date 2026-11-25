import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../services/toast.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const toastService = inject(ToastService);
  const router = inject(Router);

  const isApiCall = req.url.startsWith('/api/') || req.url.includes('/api/v1/');
  let outboundReq = req;

  if (isApiCall) {
    const token = authService.getAccessToken();
    const correlationId =
      typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
        ? crypto.randomUUID()
        : `ui-${Date.now()}`;

    const setHeaders: Record<string, string> = {
      'X-Correlation-Id': correlationId,
    };
    if (token) {
      setHeaders['Authorization'] = `Bearer ${token}`;
    }
    outboundReq = req.clone({ setHeaders });
  }

  return next(outboundReq).pipe(
    catchError((err: HttpErrorResponse) => {
      if (isApiCall) {
        const problemDetail = extractErrorMessage(err);
        if (err.status === 401) {
          toastService.show('error', 'Session expired or unauthorized. Please sign in again.');
          router.navigate(['/login']);
        } else if (err.status === 403) {
          toastService.show('error', `Forbidden: ${problemDetail}`);
        } else if (err.status === 503) {
          toastService.show('error', `Service unavailable: ${problemDetail}`);
        } else if (err.status >= 400) {
          toastService.show('error', problemDetail);
        }
      }
      return throwError(() => err);
    })
  );
};

function extractErrorMessage(err: HttpErrorResponse): string {
  if (err.error && typeof err.error === 'object') {
    const body = err.error as Record<string, unknown>;
    if (typeof body['detail'] === 'string' && body['detail']) {
      return body['detail'];
    }
    if (typeof body['message'] === 'string' && body['message']) {
      return body['message'];
    }
    if (typeof body['title'] === 'string' && body['title']) {
      return body['title'];
    }
  }
  return err.statusText || err.message || 'Unexpected API error';
}
