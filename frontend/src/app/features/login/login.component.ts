import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import { Router } from '@angular/router';
import { isDemoMode } from '../../core/auth/auth.config';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'app-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="login-container">
      <div class="card login-card">
        @if (demoOnly) {
          <div class="brand-badge">Vercel Interactive Sandbox (Simulated Backend)</div>
          <h1>Explore NexusFin AI</h1>
          <p class="muted">
            Because this deployment hosts only the Angular UI on Vercel without a live Java/Kafka
            cluster, choose a persona below to explore double-entry ledgers, transfers, live feed
            updates, and AI fraud triage right in your browser.
          </p>

          <div class="demo-btn-group">
            <button
              type="button"
              class="btn btn-primary btn-lg"
              (click)="signInDemo('ADMIN')"
              data-testid="demo-admin-btn"
            >
              Enter Sandbox as Admin (admin1)
            </button>
            <button
              type="button"
              class="btn btn-secondary btn-lg"
              (click)="signInDemo('CUSTOMER')"
              data-testid="demo-customer-btn"
            >
              Enter Sandbox as Customer (customer1)
            </button>
          </div>
        } @else {
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
            <h3>Local Credentials</h3>
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
            <div class="sandbox-alt">
              <button type="button" class="btn btn-sm btn-secondary" (click)="signInDemo('ADMIN')">
                Launch Browser Sandbox (Simulated)
              </button>
            </div>
          </div>
        }
      </div>
    </section>
  `,
})
export class LoginComponent {
  readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  readonly demoOnly = isDemoMode();

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

  signInDemo(role: 'ADMIN' | 'CUSTOMER'): void {
    this.auth.loginAsDemo(role);
    void this.router.navigate(['/accounts']);
  }
}
