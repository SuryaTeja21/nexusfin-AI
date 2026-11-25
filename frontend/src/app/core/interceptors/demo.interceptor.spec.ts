import { HttpClient, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AuthService } from '../auth/auth.service';
import { AppUser } from '../models/nexusfin.models';
import { demoInterceptor } from './demo.interceptor';

describe('demoInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  const userSig = signal<AppUser | null>(null);
  const demoSessionSig = signal(false);

  const authStub = {
    user: userSig.asReadonly(),
    isDemoSession: demoSessionSig.asReadonly(),
    isAdmin: () => userSig()?.roles.includes('ADMIN') ?? false,
  };

  beforeEach(() => {
    delete window.__NEXUSFIN_CONFIG__;
    window.sessionStorage.clear();
    userSig.set(null);
    demoSessionSig.set(false);

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([demoInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: authStub },
      ],
    });

    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    delete window.__NEXUSFIN_CONFIG__;
    window.sessionStorage.clear();
  });

  it('passes API calls through to HttpBackend when not in demo mode', () => {
    http.get('/api/v1/accounts').subscribe();
    const req = httpMock.expectOne('/api/v1/accounts');
    req.flush([]);
  });

  it('serves accounts and rejects CUSTOMER admin fraud access with 403 in demo mode', () => {
    demoSessionSig.set(true);
    userSig.set({
      username: 'customer1',
      email: 'customer1@nexusfin.io',
      subject: 'c0000000-0000-0000-0000-000000000001',
      roles: ['CUSTOMER'],
    });

    let accountsCount = 0;
    http.get<unknown[]>('/api/v1/accounts').subscribe((list) => {
      accountsCount = list.length;
    });
    expect(accountsCount).toBeGreaterThan(0);

    let errStatus = 0;
    http.get('/api/v1/fraud/audit-logs').subscribe({
      error: (err: HttpErrorResponse) => {
        errStatus = err.status;
      },
    });
    expect(errStatus).toBe(403);
  });
});
