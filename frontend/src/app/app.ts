import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { isDemoMode } from './core/auth/auth.config';
import { AuthService } from './core/auth/auth.service';
import { LiveFeedService } from './core/services/live-feed.service';
import { ToastService } from './core/services/toast.service';

@Component({
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  selector: 'app-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  readonly auth = inject(AuthService);
  readonly toastService = inject(ToastService);
  private readonly liveFeed = inject(LiveFeedService);
  private readonly router = inject(Router);

  constructor() {
    effect(() => {
      if (!this.auth.isAuthenticated()) {
        this.liveFeed.disconnect();
      }
    });
  }

  get showSandboxBanner(): boolean {
    return this.auth.isDemoSession() || isDemoMode();
  }

  logout(): void {
    const wasDemo = this.auth.isDemoSession();
    this.liveFeed.disconnect();
    this.auth.logout();
    if (wasDemo) {
      void this.router.navigate(['/login']);
    }
  }
}
