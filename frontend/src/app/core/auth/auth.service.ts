import { Injectable, computed, inject, signal } from '@angular/core';
import { OAuthService } from 'angular-oauth2-oidc';
import { buildAuthConfig } from './auth.config';
import { AppUser } from '../models/nexusfin.models';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly oauthService = inject(OAuthService);

  private readonly _user = signal<AppUser | null>(null);
  private readonly _initialized = signal(false);

  readonly user = this._user.asReadonly();
  readonly initialized = this._initialized.asReadonly();
  readonly isAuthenticated = computed(() => this._user() !== null && this.oauthService.hasValidAccessToken());
  readonly isAdmin = computed(() => this.hasRole('ADMIN'));
  readonly isCustomer = computed(() => this.hasRole('CUSTOMER'));

  async initialize(): Promise<void> {
    if (this._initialized()) {
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
    this.oauthService.configure(buildAuthConfig());
    if (!this.oauthService.discoveryDocumentLoaded) {
      await this.oauthService.loadDiscoveryDocument();
    }
    this.oauthService.initCodeFlow();
  }

  logout(): void {
    this._user.set(null);
    this.oauthService.logOut();
  }

  getAccessToken(): string {
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
