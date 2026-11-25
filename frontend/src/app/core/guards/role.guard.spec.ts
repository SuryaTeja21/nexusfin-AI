import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../services/toast.service';
import { authGuard, roleGuard } from './role.guard';

describe('authGuard and roleGuard', () => {
  let authenticated = false;
  let roles: string[] = [];

  const authStub = {
    isAuthenticated: () => authenticated,
    hasRole: (role: string) => roles.includes(role.toUpperCase()),
  };

  beforeEach(() => {
    authenticated = false;
    roles = [];

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        ToastService,
        { provide: AuthService, useValue: authStub },
      ],
    });
  });

  it('authGuard redirects unauthenticated users to /login', () => {
    const result = TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot)
    );
    expect(result instanceof UrlTree).toBe(true);
    expect((result as UrlTree).toString()).toBe('/login');
  });

  it('authGuard allows authenticated users', () => {
    authenticated = true;
    const result = TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot)
    );
    expect(result).toBe(true);
  });

  it('roleGuard allows ADMIN users when ADMIN is required', () => {
    authenticated = true;
    roles = ['ADMIN'];
    const route = { data: { roles: ['ADMIN'] } } as unknown as ActivatedRouteSnapshot;

    const result = TestBed.runInInjectionContext(() =>
      roleGuard(route, {} as RouterStateSnapshot)
    );
    expect(result).toBe(true);
  });

  it('roleGuard blocks CUSTOMER users from ADMIN routes and notifies via ToastService', () => {
    authenticated = true;
    roles = ['CUSTOMER'];
    const route = { data: { roles: ['ADMIN'] } } as unknown as ActivatedRouteSnapshot;
    const toastService = TestBed.inject(ToastService);

    const result = TestBed.runInInjectionContext(() =>
      roleGuard(route, {} as RouterStateSnapshot)
    );

    expect(result instanceof UrlTree).toBe(true);
    expect((result as UrlTree).toString()).toBe('/accounts');
    expect(toastService.messages().length).toBe(1);
    expect(toastService.messages()[0].text).toContain('insufficient role privileges');
  });
});
