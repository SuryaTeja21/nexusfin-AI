import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
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

  constructor() {
    effect(() => {
      if (!this.auth.isAuthenticated()) {
        this.liveFeed.disconnect();
      }
    });
  }

  logout(): void {
    this.liveFeed.disconnect();
    this.auth.logout();
  }
}
