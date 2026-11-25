import { TestBed } from '@angular/core/testing';
import { OAuthService } from 'angular-oauth2-oidc';
import { Subject } from 'rxjs';
import { AuthService } from './auth.service';

function makeFakeJwt(payload: Record<string, unknown>): string {
  const header = btoa(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const body = btoa(JSON.stringify(payload));
  return `${header}.${body}.signature`;
}

describe('AuthService', () => {
  let validToken = false;
  let fakeAccessToken = '';

  const oauthStub = {
    configure: vi.fn(),
    loadDiscoveryDocumentAndTryLogin: vi.fn().mockResolvedValue(true),
    loadDiscoveryDocument: vi.fn().mockResolvedValue(true),
    initCodeFlow: vi.fn(),
    logOut: vi.fn(),
    hasValidAccessToken: () => validToken,
    getAccessToken: () => fakeAccessToken,
    getIdentityClaims: () => ({}),
    discoveryDocumentLoaded: true,
    events: new Subject(),
  };

  beforeEach(() => {
    validToken = false;
    fakeAccessToken = '';
    vi.clearAllMocks();

    TestBed.configureTestingModule({
      providers: [
        AuthService,
        { provide: OAuthService, useValue: oauthStub },
      ],
    });
  });

  it('extracts user identity and uppercase realm_access roles from JWT', async () => {
    validToken = true;
    fakeAccessToken = makeFakeJwt({
      sub: 'admin-uuid-1',
      preferred_username: 'admin1',
      email: 'admin1@nexusfin.local',
      realm_access: { roles: ['admin', 'offline_access'] },
    });

    const service = TestBed.inject(AuthService);
    await service.initialize();

    expect(service.isAuthenticated()).toBe(true);
    expect(service.isAdmin()).toBe(true);
    expect(service.isCustomer()).toBe(false);
    expect(service.user()?.username).toBe('admin1');
  });

  it('clears user state on logout', async () => {
    validToken = true;
    fakeAccessToken = makeFakeJwt({
      sub: 'cust-1',
      preferred_username: 'customer1',
      realm_access: { roles: ['CUSTOMER'] },
    });

    const service = TestBed.inject(AuthService);
    await service.initialize();
    expect(service.isCustomer()).toBe(true);

    service.logout();
    expect(service.user()).toBeNull();
    expect(oauthStub.logOut).toHaveBeenCalledTimes(1);
  });
});
