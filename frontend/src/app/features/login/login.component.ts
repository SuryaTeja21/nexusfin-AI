import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'app-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="login-container">
      <div class="card login-card">
        <div class="brand-badge">OAuth2 Authorization Code + PKCE (S256)</div>
        <h1>Sign in to NexusFin AI</h1>
        <p class="muted">
          Authenticate securely through Keycloak SSO to access real-time double-entry ledgers,
          transfers, live STOMP feeds, and AI fraud monitoring.
        </p>

        @if (!auth.initialized()) {
          <div class="info-banner">Initializing OpenID Connect discovery session...</div>
        } @else {
          <button
            type="button"
            class="btn btn-primary btn-lg"
            (click)="signIn()"
            data-testid="keycloak-login-btn"
          >
            Sign In with Keycloak
          </button>
        }

        <div class="sandbox-box">
          <h3>Local Sandbox Credentials</h3>
          <div class="cred-grid">
            <div>
              <span class="role-pill role-admin">ADMIN</span>
              <code>admin1</code> / <code>admin_password</code>
            </div>
            <div>
              <span class="role-pill role-customer">CUSTOMER</span>
              <code>customer1</code> / <code>customer_password</code>
            </div>
          </div>
        </div>
      </div>
    </section>
  `,
})
export class LoginComponent {
  readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  constructor() {
    effect(() => {
      if (this.auth.initialized() && this.auth.isAuthenticated()) {
        void this.router.navigate(['/accounts']);
      }
    });
  }

  signIn(): void {
    void this.auth.login();
  }
}
