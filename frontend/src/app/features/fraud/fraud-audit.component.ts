import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { AuditLogEntry, DltMessagesResponse, RiskLevel } from '../../core/models/nexusfin.models';
import { FraudApiService } from '../../core/services/fraud-api.service';
import { ToastService } from '../../core/services/toast.service';

@Component({
  selector: 'app-fraud-audit',
  imports: [CurrencyPipe, DatePipe, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page-header">
      <div>
        <h1>AI Fraud Decisions & DLT Console</h1>
        <p class="muted">
          Recent risk evaluations from AI Fraud Service and Dead Letter Topic (DLT) recovery controls.
        </p>
      </div>
      <button type="button" class="btn btn-secondary" (click)="refreshAll()" [disabled]="loading()">
        {{ loading() ? 'Refreshing...' : 'Refresh All' }}
      </button>
    </div>

    <div class="kpi-grid">
      <div class="card kpi-card">
        <span class="kpi-label">Recent Evaluations</span>
        <strong class="kpi-value">{{ auditLogs().length }}</strong>
      </div>
      <div class="card kpi-card">
        <span class="kpi-label">Flagged / High Risk</span>
        <strong class="kpi-value">{{ flaggedCount() }}</strong>
      </div>
      <div class="card kpi-card">
        <span class="kpi-label">Active DLT Quarantine</span>
        <strong class="kpi-value">{{ dltData()?.activeQuarantinedCount ?? 0 }}</strong>
      </div>
    </div>

    <section class="card section-gap">
      <h2>Fraud Audit Log (Latest 50)</h2>
      @if (auditLogs().length === 0) {
        <p class="muted">No fraud decisions logged yet. Submit a transfer to trigger asynchronous AI scoring.</p>
      } @else {
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Tx Reference</th>
                <th class="num">Amount</th>
                <th>Risk Level</th>
                <th>Score</th>
                <th>Decision</th>
                <th>Engine</th>
                <th>Reasoning</th>
                <th>Timestamp</th>
              </tr>
            </thead>
            <tbody>
              @for (log of auditLogs(); track log.id) {
                <tr>
                  <td class="mono">{{ log.transactionReference }}</td>
                  <td class="num mono">{{ log.amount | currency: (log.currency || 'USD') }}</td>
                  <td>
                    <span class="badge" [class]="riskBadgeClass(log.riskLevel)">
                      {{ log.riskLevel }}
                    </span>
                  </td>
                  <td>
                    <div class="score-cell">
                      <span class="mono">{{ log.riskScore | number: '1.0-2' }}</span>
                      <div class="score-track">
                        <div class="score-fill" [style.width.%]="clampScore(log.riskScore)"></div>
                      </div>
                    </div>
                  </td>
                  <td><span class="tag">{{ log.decision }}</span></td>
                  <td class="small-text">{{ log.engine || log.modelName || '—' }}</td>
                  <td class="reasoning-cell">{{ log.reasoning || '—' }}</td>
                  <td class="small-text">{{ log.createdAt | date: 'short' }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </section>

    <section class="card section-gap">
      <div class="page-header compact">
        <div>
          <h2>Dead Letter Topic Quarantine</h2>
          <p class="muted">Total quarantined records: {{ dltData()?.totalCount ?? 0 }}</p>
        </div>
      </div>

      @if ((dltData()?.messages?.length ?? 0) === 0) {
        <p class="muted">DLT quarantine is empty — all Kafka events processed cleanly.</p>
      } @else {
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>ID</th>
                <th>Topic / Partition</th>
                <th>Exception</th>
                <th>Quarantined At</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              @for (msg of dltData()!.messages; track msg.id) {
                <tr>
                  <td class="mono small-text">{{ msg.id }}</td>
                  <td class="mono small-text">{{ msg.originalTopic }} [p{{ msg.partition }}:{{ msg.offset }}]</td>
                  <td class="small-text">
                    <strong>{{ msg.exceptionClass || 'Error' }}</strong>:
                    {{ msg.exceptionMessage || 'No details' }}
                  </td>
                  <td class="small-text">{{ msg.quarantinedAt | date: 'short' }}</td>
                  <td>
                    <button
                      type="button"
                      class="btn btn-sm btn-primary"
                      (click)="replay(msg.id)"
                      [disabled]="replayingId() === msg.id"
                    >
                      {{ replayingId() === msg.id ? 'Replaying...' : 'Replay' }}
                    </button>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </section>
  `,
})
export class FraudAuditComponent implements OnInit {
  private readonly fraudApi = inject(FraudApiService);
  private readonly toastService = inject(ToastService);

  readonly auditLogs = signal<AuditLogEntry[]>([]);
  readonly dltData = signal<DltMessagesResponse | null>(null);
  readonly loading = signal(false);
  readonly replayingId = signal<string | null>(null);

  readonly flaggedCount = computed(
    () => this.auditLogs().filter((l) => l.decision !== 'APPROVED' || l.riskLevel === 'HIGH' || l.riskLevel === 'CRITICAL').length
  );

  ngOnInit(): void {
    this.refreshAll();
  }

  refreshAll(): void {
    this.loading.set(true);
    this.fraudApi.getRecentAuditLogs().subscribe({
      next: (logs) => {
        this.auditLogs.set(logs);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
      },
    });

    this.fraudApi.getDltMessages().subscribe({
      next: (dlt) => this.dltData.set(dlt),
      error: () => {
        // Error surfaced globally by authInterceptor
      },
    });
  }

  replay(id: string): void {
    this.replayingId.set(id);
    this.fraudApi.replayDltMessage(id).subscribe({
      next: (res) => {
        this.replayingId.set(null);
        this.toastService.show('success', `Replayed message ${res.messageId} to ${res.targetTopic}`);
        this.refreshAll();
      },
      error: () => {
        this.replayingId.set(null);
      },
    });
  }

  clampScore(score: number): number {
    if (!Number.isFinite(score)) return 0;
    const scaled = score <= 1 ? score * 100 : score;
    return Math.max(0, Math.min(100, scaled));
  }

  riskBadgeClass(level: RiskLevel): string {
    switch (level) {
      case 'LOW':
        return 'badge-low';
      case 'MEDIUM':
        return 'badge-medium';
      case 'HIGH':
        return 'badge-high';
      case 'CRITICAL':
        return 'badge-critical';
    }
  }
}
