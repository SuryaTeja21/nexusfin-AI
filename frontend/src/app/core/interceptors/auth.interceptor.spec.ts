import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../services/toast.service';
import { authInterceptor } from './auth.interceptor';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let toastService: ToastService;
  let routerSpy: { navigate: ReturnType<typeof vi.fn> };
  let token = 'test-jwt-token';

  beforeEach(() => {
    token = 'test-jwt-token';
    routerSpy = { navigate: vi.fn().mockResolvedValue(true) };

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        ToastService,
        { provide: AuthService, useValue: { getAccessToken: () => token } },
        { provide: Router, useValue: routerSpy },
      ],
    });

    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    toastService = TestBed.inject(ToastService);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('adds Bearer Authorization and X-Correlation-Id headers on API requests', () => {
    http.get('/api/v1/accounts').subscribe();

    const req = httpMock.expectOne('/api/v1/accounts');
    expect(req.request.headers.get('Authorization')).toBe('Bearer test-jwt-token');
    expect(req.request.headers.get('X-Correlation-Id')).toBeTruthy();
    req.flush([]);
  });

  it('does not attach auth headers on non-API URLs', () => {
    const discoveryUrl = 'http://localhost:8083/realms/nexusfin/.well-known/openid-configuration';
    http.get(discoveryUrl).subscribe();

    const req = httpMock.expectOne(discoveryUrl);
    expect(req.request.headers.has('Authorization')).toBe(false);
    expect(req.request.headers.has('X-Correlation-Id')).toBe(false);
    req.flush({});
  });

  it('displays RFC 7807 detail toast on 403 Forbidden API responses', () => {
    let errored = false;
    http.get('/api/v1/fraud/audit-logs').subscribe({
      error: () => {
        errored = true;
      },
    });

    const req = httpMock.expectOne('/api/v1/fraud/audit-logs');
    req.flush(
      {
        title: 'Forbidden',
        status: 403,
        detail: 'ADMIN role is required to view fraud audit logs',
      },
      { status: 403, statusText: 'Forbidden' }
    );

    expect(errored).toBe(true);
    expect(toastService.messages().length).toBe(1);
    expect(toastService.messages()[0].kind).toBe('error');
    expect(toastService.messages()[0].text).toContain('ADMIN role is required');
  });

  it('shows error toast and redirects to /login on 401 Unauthorized', () => {
    let errored = false;
    http.get('/api/v1/accounts').subscribe({
      error: () => {
        errored = true;
      },
    });

    const req = httpMock.expectOne('/api/v1/accounts');
    req.flush({}, { status: 401, statusText: 'Unauthorized' });

    expect(errored).toBe(true);
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/login']);
    expect(toastService.messages()[0].text).toContain('Session expired or unauthorized');
  });
});
