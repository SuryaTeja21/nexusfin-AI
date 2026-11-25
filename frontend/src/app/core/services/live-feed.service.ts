import { Injectable, OnDestroy, inject, signal } from '@angular/core';
import { RxStomp, RxStompState } from '@stomp/rx-stomp';
import { Subscription } from 'rxjs';
import { getWsBrokerUrl } from '../auth/auth.config';
import { AuthService } from '../auth/auth.service';
import { LiveTransactionEvent } from '../models/nexusfin.models';

@Injectable({ providedIn: 'root' })
export class LiveFeedService implements OnDestroy {
  private readonly authService = inject(AuthService);

  private rxStomp: RxStomp | null = null;
  private stateSub: Subscription | null = null;
  private feedSub: Subscription | null = null;

  readonly connectionState = signal<RxStompState>(RxStompState.CLOSED);
  readonly subscribedTopic = signal<string>('');
  readonly events = signal<LiveTransactionEvent[]>([]);

  connect(): void {
    const token = this.authService.getAccessToken();
    if (!token) {
      return;
    }
    if (this.rxStomp && this.rxStomp.connected()) {
      return;
    }

    this.disconnect();

    const destination = this.authService.isAdmin() ? '/topic/transactions' : '/user/queue/transactions';
    this.subscribedTopic.set(destination);

    const stomp = new RxStomp();
    stomp.configure({
      brokerURL: getWsBrokerUrl(),
      connectHeaders: {
        Authorization: `Bearer ${token}`,
      },
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      reconnectDelay: 3000,
    });

    this.stateSub = stomp.connectionState$.subscribe((state) => {
      this.connectionState.set(state);
    });

    this.feedSub = stomp.watch({ destination }).subscribe((message) => {
      try {
        const parsed = JSON.parse(message.body) as LiveTransactionEvent;
        this.events.update((prev) => [parsed, ...prev].slice(0, 50));
      } catch {
        // Ignore unparseable frames safely
      }
    });

    stomp.activate();
    this.rxStomp = stomp;
  }

  disconnect(): void {
    this.feedSub?.unsubscribe();
    this.stateSub?.unsubscribe();
    this.feedSub = null;
    this.stateSub = null;
    if (this.rxStomp) {
      void this.rxStomp.deactivate();
      this.rxStomp = null;
    }
    this.connectionState.set(RxStompState.CLOSED);
  }

  clearEvents(): void {
    this.events.set([]);
  }

  ngOnDestroy(): void {
    this.disconnect();
  }
}
