import { HttpErrorResponse, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { of, throwError } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { isDemoMode } from '../auth/auth.config';
import { CreateAccountRequest, TransferFundsRequest } from '../models/nexusfin.models';
import { DemoBackendService } from '../services/demo-backend.service';

export const demoInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  if (!isDemoMode() && !authService.isDemoSession()) {
    return next(req);
  }

  const demoBackend = inject(DemoBackendService);
  const path = normalizePath(req.url);
  if (!path.startsWith('/api/v1/')) {
    return next(req);
  }

  const user = authService.user();
  if (!user) {
    return problemError(req.url, 401, 'Unauthorized', 'Sign in to access simulated endpoints.');
  }

  const isAdmin = authService.isAdmin();

  try {
    if (req.method === 'GET' && path === '/api/v1/accounts') {
      return of(new HttpResponse({ status: 200, body: demoBackend.getAccounts(isAdmin, user.subject) }));
    }

    if (req.method === 'POST' && path === '/api/v1/accounts') {
      if (!isAdmin) {
        return problemError(req.url, 403, 'Forbidden', 'ADMIN role is required to create accounts.');
      }
      const created = demoBackend.createAccount(req.body as CreateAccountRequest);
      return of(new HttpResponse({ status: 201, body: created }));
    }

    const entriesMatch = path.match(/^\/api\/v1\/accounts\/([^/]+)\/entries$/);
    if (req.method === 'GET' && entriesMatch) {
      const entries = demoBackend.getAccountEntries(entriesMatch[1], isAdmin, user.subject);
      return of(new HttpResponse({ status: 200, body: entries }));
    }

    if (req.method === 'POST' && path === '/api/v1/ledger/transfers') {
      const tx = demoBackend.transferFunds(req.body as TransferFundsRequest, isAdmin, user.subject);
      return of(new HttpResponse({ status: 201, body: tx }));
    }

    if (req.method === 'GET' && path === '/api/v1/fraud/audit-logs') {
      if (!isAdmin) {
        return problemError(req.url, 403, 'Forbidden', 'ADMIN role is required to view fraud audit logs.');
      }
      return of(new HttpResponse({ status: 200, body: demoBackend.getRecentAuditLogs() }));
    }

    if (req.method === 'GET' && path === '/api/v1/fraud/dlt/messages') {
      if (!isAdmin) {
        return problemError(req.url, 403, 'Forbidden', 'ADMIN role is required to inspect DLT queue.');
      }
      return of(new HttpResponse({ status: 200, body: demoBackend.getDltMessages() }));
    }

    const replayMatch = path.match(/^\/api\/v1\/fraud\/dlt\/replay\/([^/]+)$/);
    if (req.method === 'POST' && replayMatch) {
      if (!isAdmin) {
        return problemError(req.url, 403, 'Forbidden', 'ADMIN role is required to replay DLT messages.');
      }
      const res = demoBackend.replayDltMessage(decodeURIComponent(replayMatch[1]));
      return of(new HttpResponse({ status: 200, body: res }));
    }

    return problemError(req.url, 404, 'Not Found', `Simulated endpoint not implemented: ${req.method} ${path}`);
  } catch (err: unknown) {
    const custom = err as { status?: number; detail?: string };
    return problemError(
      req.url,
      custom.status ?? 400,
      'Sandbox Validation Error',
      custom.detail ?? 'Simulated request failed.'
    );
  }
};

function normalizePath(url: string): string {
  try {
    return url.startsWith('http') ? new URL(url).pathname : url.split('?')[0];
  } catch {
    return url;
  }
}

function problemError(url: string, status: number, title: string, detail: string) {
  return throwError(
    () =>
      new HttpErrorResponse({
        url,
        status,
        statusText: title,
        error: { title, status, detail },
      })
  );
}
