import { Injectable, computed, inject, signal } from '@angular/core';
import { OAuthService } from 'angular-oauth2-oidc';
import { AppUser } from '../models/nexusfin.models';
import { buildAuthConfig, isDemoMode } from './auth.config';

const DEMO_USERS: Record<'ADMIN' | 'CUSTOMER', AppUser> = {
  ADMIN: {
    username: 'admin1',
    email: 'admin1@nexusfin.io',
    subject: 'a0000000-0000-0000-0000-000000000001',
    roles: ['ADMIN'],
  },
  CUSTOMER: {
    username: 'customer1',
    email: 'customer1@nexusfin.io',
    subject: 'c0000000-0000-0000-0000-000000000001',
    roles: ['CUSTOMER'],
  },
};

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly oauthService = inject(OAuthService);

  private readonly _user = signal<AppUser | null>(null);
  private readonly _initialized = signal(false);
  private readonly _isDemoSession = signal(false);

  readonly user = this._user.asReadonly();
  readonly initialized = this._initialized.asReadonly();
  readonly isDemoSession = this._isDemoSession.asReadonly();
  readonly sandboxAvailable = computed(() => isDemoMode());

  readonly isAuthenticated = computed(
    () => this._user() !== null && (this._isDemoSession() || this.oauthService.hasValidAccessToken())
  );
  readonly isAdmin = computed(() => this.hasRole('ADMIN'));
  readonly isCustomer = computed(() => this.hasRole('CUSTOMER'));

  async initialize(): Promise<void> {
    if (this._initialized()) {
      return;
    }

    if (isDemoMode()) {
      const savedRole = typeof window !== 'undefined' ? window.sessionStorage?.getItem('nexusfin_demo_role') : null;
      if (savedRole === 'ADMIN' || savedRole === 'CUSTOMER') {
        this._isDemoSession.set(true);
        this._user.set(DEMO_USERS[savedRole]);
      }
      this._initialized.set(true);
      return;
    }

    this.oauthService.configure(buildAuthConfig());
    try {
      await this.oauthService.loadDiscoveryDocumentAndTryLogin();
    } catch {
      // Discovery endpoint may be offline during isolated tests; state stays unauthenticated
    }
    this.syncUserFromTokens();
    this.oauthService.events.subscribe(() => this.syncUserFromTokens());
    this._initialized.set(true);
  }

  async login(): Promise<void> {
    if (isDemoMode()) {
      this.loginAsDemo('ADMIN');
      return;
    }
    this.oauthService.configure(buildAuthConfig());
    if (!this.oauthService.discoveryDocumentLoaded) {
      await this.oauthService.loadDiscoveryDocument();
    }
    this.oauthService.initCodeFlow();
  }

  loginAsDemo(role: 'ADMIN' | 'CUSTOMER'): void {
    if (typeof window !== 'undefined') {
      window.sessionStorage?.setItem('nexusfin_demo_active', 'true');
      window.sessionStorage?.setItem('nexusfin_demo_role', role);
    }
    this._isDemoSession.set(true);
    this._user.set(DEMO_USERS[role]);
  }

  logout(): void {
    const wasDemo = this._isDemoSession();
    this._user.set(null);
    this._isDemoSession.set(false);
    if (typeof window !== 'undefined') {
      window.sessionStorage?.removeItem('nexusfin_demo_role');
      window.sessionStorage?.removeItem('nexusfin_demo_active');
    }
    if (!wasDemo) {
      this.oauthService.logOut();
    }
  }

  getAccessToken(): string {
    if (this._isDemoSession()) {
      return 'sandbox-demo-token';
    }
    return this.oauthService.getAccessToken() || '';
  }

  hasRole(role: string): boolean {
    const current = this._user();
    if (!current) {
      return false;
    }
    const target = role.toUpperCase();
    return current.roles.some((r) => r.toUpperCase() === target);
  }

  syncUserFromTokens(): void {
    if (this._isDemoSession()) {
      return;
    }
    if (!this.oauthService.hasValidAccessToken()) {
      this._user.set(null);
      return;
    }

    const accessToken = this.oauthService.getAccessToken();
    const accessClaims = this.decodeJwtPayload(accessToken);
    const idClaims = (this.oauthService.getIdentityClaims() as Record<string, unknown>) || {};

    const realmAccess = accessClaims['realm_access'] as { roles?: string[] } | undefined;
    const rawRoles = Array.isArray(realmAccess?.roles) ? realmAccess.roles : [];

    this._user.set({
      username: String(accessClaims['preferred_username'] ?? idClaims['preferred_username'] ?? accessClaims['sub'] ?? 'user'),
      email: String(accessClaims['email'] ?? idClaims['email'] ?? ''),
      subject: String(accessClaims['sub'] ?? idClaims['sub'] ?? ''),
      roles: rawRoles.map((r) => String(r).toUpperCase()),
    });
  }

  private decodeJwtPayload(token: string): Record<string, unknown> {
    if (!token || !token.includes('.')) {
      return {};
    }
    try {
      const payloadSegment = token.split('.')[1];
      const base64 = payloadSegment.replace(/-/g, '+').replace(/_/g, '/');
      const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
      return JSON.parse(atob(padded)) as Record<string, unknown>;
    } catch {
      return {};
    }
  }
}
