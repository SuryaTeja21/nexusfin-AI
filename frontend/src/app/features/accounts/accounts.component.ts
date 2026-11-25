import { CurrencyPipe, DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AuthService } from '../../core/auth/auth.service';
import { AccountResponse, AccountType, LedgerEntryResponse } from '../../core/models/nexusfin.models';
import { LedgerApiService } from '../../core/services/ledger-api.service';
import { ToastService } from '../../core/services/toast.service';

@Component({
  selector: 'app-accounts',
  imports: [ReactiveFormsModule, CurrencyPipe, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page-header">
      <div>
        <h1>Accounts & Ledger</h1>
        <p class="muted">
          @if (auth.isAdmin()) {
            Full administrative view across all tenant ledger accounts.
          } @else {
            Your owned ledger accounts and immutable double-entry postings.
          }
        </p>
      </div>
      <div class="actions-row">
        <button type="button" class="btn btn-secondary" (click)="loadAccounts()" [disabled]="loading()">
          {{ loading() ? 'Refreshing...' : 'Refresh' }}
        </button>
        @if (auth.isAdmin()) {
          <button type="button" class="btn btn-primary" (click)="toggleCreateForm()">
            {{ showCreateForm() ? 'Close Form' : '+ Create Account' }}
          </button>
        }
      </div>
    </div>

    <div class="kpi-grid">
      <div class="card kpi-card">
        <span class="kpi-label">Visible Accounts</span>
        <strong class="kpi-value">{{ accounts().length }}</strong>
      </div>
      <div class="card kpi-card">
        <span class="kpi-label">Total USD Liquidity</span>
        <strong class="kpi-value mono">{{ totalUsdBalance() | currency: 'USD' }}</strong>
      </div>
      <div class="card kpi-card">
        <span class="kpi-label">Active Access Role</span>
        <strong class="kpi-value">{{ auth.isAdmin() ? 'ADMIN' : 'CUSTOMER' }}</strong>
      </div>
    </div>

    @if (auth.isAdmin() && showCreateForm()) {
      <section class="card section-gap">
        <h2>Provision New Account</h2>
        <form [formGroup]="createForm" (ngSubmit)="submitCreate()" class="form-grid">
          <label class="field">
            <span>Account Number</span>
            <input type="text" formControlName="accountNumber" placeholder="ACC-2001" />
          </label>
          <label class="field">
            <span>Account Name</span>
            <input type="text" formControlName="accountName" placeholder="Operating Checking" />
          </label>
          <label class="field">
            <span>Account Type</span>
            <select formControlName="accountType">
              <option value="ASSET">ASSET</option>
              <option value="LIABILITY">LIABILITY</option>
              <option value="EQUITY">EQUITY</option>
              <option value="REVENUE">REVENUE</option>
              <option value="EXPENSE">EXPENSE</option>
            </select>
          </label>
          <label class="field">
            <span>Currency</span>
            <select formControlName="currency">
              <option value="USD">USD</option>
              <option value="EUR">EUR</option>
              <option value="GBP">GBP</option>
            </select>
          </label>
          <label class="field">
            <span>Initial Balance</span>
            <input type="number" step="0.01" min="0" formControlName="initialBalance" />
          </label>
          <label class="field">
            <span>Owner Subject / ID (optional)</span>
            <input type="text" formControlName="ownerId" placeholder="Keycloak user sub or username" />
          </label>
          <div class="form-actions full-width">
            <button type="submit" class="btn btn-primary" [disabled]="createForm.invalid || creating()">
              {{ creating() ? 'Creating...' : 'Create Account' }}
            </button>
          </div>
        </form>
      </section>
    }

    <section class="card section-gap">
      @if (loading() && accounts().length === 0) {
        <p class="muted">Loading ledger accounts...</p>
      } @else if (accounts().length === 0) {
        <div class="empty-state">
          <p>No accounts found for your profile yet.</p>
          @if (auth.isAdmin()) {
            <p class="muted">Use <strong>+ Create Account</strong> above to provision test accounts.</p>
          }
        </div>
      } @else {
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Account #</th>
                <th>Name</th>
                <th>Type</th>
                <th>Currency</th>
                <th class="num">Balance</th>
                <th>Status</th>
                <th>Owner</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              @for (acc of accounts(); track acc.id) {
                <tr [class.row-selected]="selectedAccount()?.id === acc.id">
                  <td class="mono">{{ acc.accountNumber }}</td>
                  <td>{{ acc.accountName }}</td>
                  <td><span class="tag">{{ acc.accountType }}</span></td>
                  <td>{{ acc.currency }}</td>
                  <td class="num mono">{{ acc.balance | currency: acc.currency }}</td>
                  <td>
                    <span class="badge" [class]="statusBadgeClass(acc.status)">{{ acc.status }}</span>
                  </td>
                  <td class="mono small-text">{{ acc.ownerId || '—' }}</td>
                  <td>
                    <button type="button" class="btn btn-sm btn-secondary" (click)="inspectEntries(acc)">
                      Entries
                    </button>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </section>

    @if (selectedAccount(); as selected) {
      <section class="card section-gap">
        <div class="page-header compact">
          <div>
            <h2>Ledger Entries: <span class="mono">{{ selected.accountNumber }}</span></h2>
            <p class="muted">{{ selected.accountName }} ({{ selected.currency }})</p>
          </div>
          <button type="button" class="btn btn-sm btn-secondary" (click)="selectedAccount.set(null)">
            Close
          </button>
        </div>

        @if (entriesLoading()) {
          <p class="muted">Loading immutable ledger entries...</p>
        } @else if (entries().length === 0) {
          <p class="muted">No double-entry postings recorded for this account yet.</p>
        } @else {
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Seq #</th>
                  <th>Type</th>
                  <th class="num">Amount</th>
                  <th class="num">Balance After</th>
                  <th>Transaction ID</th>
                  <th>Posted At</th>
                </tr>
              </thead>
              <tbody>
                @for (entry of entries(); track entry.id) {
                  <tr>
                    <td class="mono">#{{ entry.sequenceNumber }}</td>
                    <td>
                      <span class="badge" [class]="entry.entryType === 'CREDIT' ? 'badge-low' : 'badge-high'">
                        {{ entry.entryType }}
                      </span>
                    </td>
                    <td class="num mono">{{ entry.amount | currency: entry.currency }}</td>
                    <td class="num mono">{{ entry.balanceAfter | currency: entry.currency }}</td>
                    <td class="mono small-text">{{ entry.transactionId }}</td>
                    <td>{{ entry.createdAt | date: 'short' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>
    }
  `,
})
export class AccountsComponent implements OnInit {
  readonly auth = inject(AuthService);
  private readonly ledgerApi = inject(LedgerApiService);
  private readonly toastService = inject(ToastService);
  private readonly fb = inject(FormBuilder);

  readonly accounts = signal<AccountResponse[]>([]);
  readonly loading = signal(false);
  readonly showCreateForm = signal(false);
  readonly creating = signal(false);

  readonly selectedAccount = signal<AccountResponse | null>(null);
  readonly entries = signal<LedgerEntryResponse[]>([]);
  readonly entriesLoading = signal(false);

  readonly totalUsdBalance = computed(() =>
    this.accounts()
      .filter((a) => a.currency === 'USD')
      .reduce((sum, acc) => sum + Number(acc.balance ?? 0), 0)
  );

  readonly createForm = this.fb.nonNullable.group({
    accountNumber: ['', [Validators.required, Validators.minLength(3), Validators.maxLength(34)]],
    accountName: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(120)]],
    accountType: ['ASSET' as AccountType, Validators.required],
    currency: ['USD', [Validators.required, Validators.minLength(3), Validators.maxLength(3)]],
    initialBalance: [1000, [Validators.required, Validators.min(0)]],
    ownerId: [''],
  });

  ngOnInit(): void {
    this.loadAccounts();
  }

  loadAccounts(): void {
    this.loading.set(true);
    this.ledgerApi.getAccounts().subscribe({
      next: (data) => {
        this.accounts.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
      },
    });
  }

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
  }

  submitCreate(): void {
    if (this.createForm.invalid || this.creating()) {
      return;
    }
    const raw = this.createForm.getRawValue();
    this.creating.set(true);
    this.ledgerApi
      .createAccount({
        accountNumber: raw.accountNumber.trim(),
        accountName: raw.accountName.trim(),
        accountType: raw.accountType,
        currency: raw.currency.toUpperCase(),
        initialBalance: Number(raw.initialBalance),
        ownerId: raw.ownerId.trim() || null,
      })
      .subscribe({
        next: (created) => {
          this.creating.set(false);
          this.showCreateForm.set(false);
          this.createForm.reset({
            accountNumber: '',
            accountName: '',
            accountType: 'ASSET',
            currency: 'USD',
            initialBalance: 1000,
            ownerId: '',
          });
          this.toastService.show('success', `Created account ${created.accountNumber}`);
          this.loadAccounts();
        },
        error: () => {
          this.creating.set(false);
        },
      });
  }

  inspectEntries(account: AccountResponse): void {
    this.selectedAccount.set(account);
    this.entriesLoading.set(true);
    this.entries.set([]);
    this.ledgerApi.getAccountEntries(account.id).subscribe({
      next: (list) => {
        this.entries.set(list);
        this.entriesLoading.set(false);
      },
      error: () => {
        this.entriesLoading.set(false);
      },
    });
  }

  statusBadgeClass(status: string): string {
    if (status === 'ACTIVE') return 'badge-low';
    if (status === 'FROZEN') return 'badge-high';
    return 'badge-critical';
  }
}
