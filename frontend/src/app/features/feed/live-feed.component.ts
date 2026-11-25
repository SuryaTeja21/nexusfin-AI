import { CurrencyPipe, DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { RxStompState } from '@stomp/rx-stomp';
import { AuthService } from '../../core/auth/auth.service';
import { LiveFeedService } from '../../core/services/live-feed.service';

@Component({
  selector: 'app-live-feed',
  imports: [CurrencyPipe, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page-header">
      <div>
        <h1>Live Transaction Feed</h1>
        <p class="muted">
          Real-time STOMP-over-WebSocket stream from Notification Service
          (<code class="mono">{{ liveFeed.subscribedTopic() || defaultTopic() }}</code>).
        </p>
      </div>
      <div class="actions-row">
        <span class="badge" [class]="connectionBadgeClass()">
          {{ connectionLabel() }}
        </span>
        @if (isConnected()) {
          <button type="button" class="btn btn-secondary" (click)="liveFeed.disconnect()">Disconnect</button>
        } @else {
          <button type="button" class="btn btn-primary" (click)="liveFeed.connect()">Connect</button>
        }
        <button type="button" class="btn btn-secondary" (click)="liveFeed.clearEvents()">Clear</button>
      </div>
    </div>

    <section class="card">
      @if (liveFeed.events().length === 0) {
        <div class="empty-state">
          <p>Listening for real-time ledger events...</p>
          <p class="muted">
            Initiate a transfer in the <strong>New Transfer</strong> view to see events arrive immediately.
          </p>
        </div>
      } @else {
        <div class="feed-list">
          @for (evt of liveFeed.events(); track evt.transactionId + (evt.postedAt ?? '')) {
            <article class="feed-item">
              <div class="feed-item-top">
                <div>
                  <strong class="mono">{{ evt.transactionReference || evt.transactionId }}</strong>
                  @if (evt.transactionType) {
                    <span class="tag">{{ evt.transactionType }}</span>
                  }
                </div>
                <div class="feed-meta">
                  @if (evt.totalVolume ?? evt.amount; as amt) {
                    <strong class="mono">{{ amt | currency: (evt.currency || 'USD') }}</strong>
                  }
                  @if (evt.postedAt) {
                    <span class="muted small-text">{{ evt.postedAt | date: 'mediumTime' }}</span>
                  }
                </div>
              </div>

              @if (evt.description) {
                <p class="muted small-text">{{ evt.description }}</p>
              }

              @if (evt.legs && evt.legs.length > 0) {
                <div class="legs-grid">
                  @for (leg of evt.legs; track leg.accountNumber + leg.entryType) {
                    <div class="leg-item">
                      <span class="badge" [class]="leg.entryType === 'CREDIT' ? 'badge-low' : 'badge-high'">
                        {{ leg.entryType }}
                      </span>
                      <span class="mono">{{ leg.accountNumber }}</span>
                      <span class="mono">{{ leg.amount | currency: (leg.currency || 'USD') }}</span>
                      @if (leg.newBalance !== undefined) {
                        <span class="muted small-text">Bal: {{ leg.newBalance | currency: (leg.currency || 'USD') }}</span>
                      }
                    </div>
                  }
                </div>
              }
            </article>
          }
        </div>
      }
    </section>
  `,
})
export class LiveFeedComponent implements OnInit {
  readonly liveFeed = inject(LiveFeedService);
  private readonly auth = inject(AuthService);

  readonly defaultTopic = computed(() =>
    this.auth.isAdmin() ? '/topic/transactions' : '/user/queue/transactions'
  );

  readonly isConnected = computed(() => this.liveFeed.connectionState() === RxStompState.OPEN);

  readonly connectionLabel = computed(() => {
    switch (this.liveFeed.connectionState()) {
      case RxStompState.OPEN:
        return 'CONNECTED';
      case RxStompState.CONNECTING:
        return 'CONNECTING';
      case RxStompState.CLOSING:
        return 'CLOSING';
      default:
        return 'DISCONNECTED';
    }
  });

  readonly connectionBadgeClass = computed(() => {
    switch (this.liveFeed.connectionState()) {
      case RxStompState.OPEN:
        return 'badge-low';
      case RxStompState.CONNECTING:
        return 'badge-medium';
      default:
        return 'badge-critical';
    }
  });

  ngOnInit(): void {
    this.liveFeed.connect();
  }
}
