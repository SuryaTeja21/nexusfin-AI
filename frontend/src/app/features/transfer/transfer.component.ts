import { CurrencyPipe, DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { v4 as uuidv4 } from 'uuid';
import { AccountResponse, TransactionResponse } from '../../core/models/nexusfin.models';
import { LedgerApiService } from '../../core/services/ledger-api.service';
import { ToastService } from '../../core/services/toast.service';

export function distinctAccountsValidator(control: AbstractControl): ValidationErrors | null {
  const source = String(control.get('sourceAccountNumber')?.value ?? '').trim();
  const dest = String(control.get('destinationAccountNumber')?.value ?? '').trim();
  if (source && dest && source === dest) {
    return { sameAccount: true };
  }
  return null;
}

@Component({
  selector: 'app-transfer',
  imports: [ReactiveFormsModule, CurrencyPipe, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page-header">
      <div>
        <h1>New Transfer</h1>
        <p class="muted">
          Execute an atomic double-entry funds transfer protected by client-side idempotency keys.
        </p>
      </div>
    </div>

    <div class="split-layout">
      <section class="card">
        <h2>Transfer Details</h2>

        @if (accounts().length > 0) {
          <div class="quick-accounts">
            <span class="small-label">Your Accounts (click to fill Source):</span>
            <div class="chip-row">
              @for (acc of accounts(); track acc.id) {
                <button
                  type="button"
                  class="chip"
                  (click)="selectSourceAccount(acc)"
                >
                  <span class="mono">{{ acc.accountNumber }}</span>
                  <span>({{ acc.balance | currency: acc.currency }})</span>
                </button>
              }
            </div>
          </div>
        }

        <form [formGroup]="transferForm" (ngSubmit)="submitTransfer()" class="stack-form">
          <label class="field">
            <span>Source Account Number</span>
            <input
              type="text"
              formControlName="sourceAccountNumber"
              placeholder="ACC-1001"
              data-testid="source-account-input"
            />
            @if (transferForm.controls.sourceAccountNumber.touched && transferForm.controls.sourceAccountNumber.invalid) {
              <span class="field-error">Source account number is required (min 3 characters).</span>
            }
          </label>

          <label class="field">
            <span>Destination Account Number</span>
            <input
              type="text"
              formControlName="destinationAccountNumber"
              placeholder="ACC-1002"
              data-testid="dest-account-input"
            />
            @if (transferForm.controls.destinationAccountNumber.touched && transferForm.controls.destinationAccountNumber.invalid) {
              <span class="field-error">Destination account number is required (min 3 characters).</span>
            }
            @if (transferForm.errors?.['sameAccount'] && transferForm.controls.destinationAccountNumber.touched) {
              <span class="field-error">Source and destination accounts must be different.</span>
            }
          </label>

          <div class="two-col">
            <label class="field">
              <span>Amount</span>
              <input
                type="number"
                step="0.01"
                min="0.01"
                formControlName="amount"
                data-testid="amount-input"
              />
              @if (transferForm.controls.amount.touched && transferForm.controls.amount.invalid) {
                <span class="field-error">Amount must be greater than 0.</span>
              }
            </label>

            <label class="field">
              <span>Currency</span>
              <select formControlName="currency" data-testid="currency-select">
                <option value="USD">USD</option>
                <option value="EUR">EUR</option>
                <option value="GBP">GBP</option>
              </select>
            </label>
          </div>

          <label class="field">
            <span>Idempotency Key (UUID v4)</span>
            <div class="input-with-btn">
              <input
                type="text"
                class="mono"
                formControlName="idempotencyKey"
                data-testid="idempotency-input"
              />
              <button type="button" class="btn btn-secondary" (click)="regenerateIdempotencyKey()">
                New UUID
              </button>
            </div>
          </label>

          <label class="field">
            <span>Description (optional)</span>
            <input
              type="text"
              formControlName="description"
              placeholder="Vendor invoice settlement"
              data-testid="description-input"
            />
          </label>

          <div class="form-actions">
            <button
              type="submit"
              class="btn btn-primary"
              [disabled]="transferForm.invalid || submitting()"
              data-testid="submit-transfer-btn"
            >
              {{ submitting() ? 'Submitting Transfer...' : 'Submit Transfer' }}
            </button>
          </div>
        </form>
      </section>

      <section class="card">
        <h2>Execution Receipt</h2>
        @if (lastReceipt(); as tx) {
          <div class="receipt-box" data-testid="transfer-receipt">
            <div class="receipt-row">
              <span class="muted">Reference</span>
              <strong class="mono">{{ tx.transactionReference }}</strong>
            </div>
            <div class="receipt-row">
              <span class="muted">Status</span>
              <span class="badge badge-low">{{ tx.status }}</span>
            </div>
            <div class="receipt-row">
              <span class="muted">Idempotency Key</span>
              <span class="mono small-text">{{ tx.idempotencyKey }}</span>
            </div>
            <div class="receipt-row">
              <span class="muted">Posted At</span>
              <span>{{ tx.postedAt | date: 'medium' }}</span>
            </div>

            <h3>Posted Ledger Legs</h3>
            @for (entry of tx.entries; track entry.id) {
              <div class="leg-item">
                <span class="badge" [class]="entry.entryType === 'CREDIT' ? 'badge-low' : 'badge-high'">
                  {{ entry.entryType }}
                </span>
                <span class="mono">{{ entry.accountNumber }}</span>
                <strong class="mono">{{ entry.amount | currency: entry.currency }}</strong>
              </div>
            }

            <div class="form-actions">
              <button type="button" class="btn btn-secondary btn-sm" (click)="prepareNextTransfer()">
                Prepare Another Transfer
              </button>
            </div>
          </div>
        } @else {
          <div class="empty-state">
            <p class="muted">
              Submit a transfer on the left to inspect its posted double-entry legs. Every transaction
              publishes to Kafka, triggers AI fraud scoring, and streams over WebSocket.
            </p>
          </div>
        }
      </section>
    </div>
  `,
})
export class TransferComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly ledgerApi = inject(LedgerApiService);
  private readonly toastService = inject(ToastService);

  readonly accounts = signal<AccountResponse[]>([]);
  readonly submitting = signal(false);
  readonly lastReceipt = signal<TransactionResponse | null>(null);

  readonly transferForm = this.fb.nonNullable.group(
    {
      sourceAccountNumber: ['', [Validators.required, Validators.minLength(3)]],
      destinationAccountNumber: ['', [Validators.required, Validators.minLength(3)]],
      amount: [100, [Validators.required, Validators.min(0.01)]],
      currency: ['USD', [Validators.required, Validators.minLength(3), Validators.maxLength(3)]],
      idempotencyKey: [uuidv4(), [Validators.required, Validators.minLength(8)]],
      description: ['', [Validators.maxLength(200)]],
    },
    { validators: distinctAccountsValidator }
  );

  ngOnInit(): void {
    this.loadAccounts();
  }

  loadAccounts(): void {
    this.ledgerApi.getAccounts().subscribe({
      next: (list) => this.accounts.set(list),
      error: () => {
        // Error surfaced globally by authInterceptor
      },
    });
  }

  selectSourceAccount(acc: AccountResponse): void {
    this.transferForm.patchValue({
      sourceAccountNumber: acc.accountNumber,
      currency: acc.currency,
    });
  }

  regenerateIdempotencyKey(): void {
    this.transferForm.patchValue({ idempotencyKey: uuidv4() });
  }

  prepareNextTransfer(): void {
    this.lastReceipt.set(null);
    this.regenerateIdempotencyKey();
  }

  submitTransfer(): void {
    this.transferForm.markAllAsTouched();
    if (this.transferForm.invalid || this.submitting()) {
      return;
    }

    const raw = this.transferForm.getRawValue();
    this.submitting.set(true);

    this.ledgerApi
      .transferFunds({
        sourceAccountNumber: raw.sourceAccountNumber.trim(),
        destinationAccountNumber: raw.destinationAccountNumber.trim(),
        amount: Number(raw.amount),
        currency: raw.currency.toUpperCase(),
        idempotencyKey: raw.idempotencyKey.trim(),
        description: raw.description.trim() || undefined,
      })
      .subscribe({
        next: (tx) => {
          this.submitting.set(false);
          this.lastReceipt.set(tx);
          this.toastService.show('success', `Transfer posted (${tx.transactionReference})`);
          this.regenerateIdempotencyKey();
          this.loadAccounts();
        },
        error: () => {
          this.submitting.set(false);
        },
      });
  }
}
