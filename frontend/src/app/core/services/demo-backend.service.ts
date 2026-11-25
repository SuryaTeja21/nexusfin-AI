import { Injectable } from '@angular/core';
import { Subject } from 'rxjs';
import {
  AccountResponse,
  AuditLogEntry,
  CreateAccountRequest,
  DltMessage,
  DltMessagesResponse,
  FraudDecision,
  LedgerEntryResponse,
  LiveTransactionEvent,
  RiskLevel,
  TransactionResponse,
  TransferFundsRequest,
} from '../models/nexusfin.models';

const CUSTOMER1_ID = 'c0000000-0000-0000-0000-000000000001';

@Injectable({ providedIn: 'root' })
export class DemoBackendService {
  private readonly _liveEvents$ = new Subject<LiveTransactionEvent>();
  readonly liveEvents$ = this._liveEvents$.asObservable();

  private accounts: AccountResponse[] = [];
  private entriesByAccount = new Map<string, LedgerEntryResponse[]>();
  private transactionsByIdempotency = new Map<string, TransactionResponse>();
  private auditLogs: AuditLogEntry[] = [];
  private dltMessages: DltMessage[] = [];
  private activeDltCount = 1;

  constructor() {
    this.seedInitialState();
  }

  private seedInitialState(): void {
    const now = new Date().toISOString();
    this.accounts = [
      {
        id: '11111111-1111-4111-8111-111111111111',
        accountNumber: 'ACC-ASSET-CENTRAL-RESERVE-USD',
        accountName: 'Central Liquidity Reserve',
        accountType: 'ASSET',
        currency: 'USD',
        balance: 250000,
        status: 'ACTIVE',
        createdAt: now,
        updatedAt: now,
        version: 1,
        ownerId: null,
      },
      {
        id: '22222222-2222-4222-8222-222222222222',
        accountNumber: 'ACC-CUSTOMER-ALICE-USD',
        accountName: 'Alice Primary Checking',
        accountType: 'LIABILITY',
        currency: 'USD',
        balance: 14850,
        status: 'ACTIVE',
        createdAt: now,
        updatedAt: now,
        version: 1,
        ownerId: CUSTOMER1_ID,
      },
      {
        id: '33333333-3333-4333-8333-333333333333',
        accountNumber: 'ACC-CUSTOMER-SAVINGS-USD',
        accountName: 'Alice Treasury Vault',
        accountType: 'LIABILITY',
        currency: 'USD',
        balance: 32000,
        status: 'ACTIVE',
        createdAt: now,
        updatedAt: now,
        version: 1,
        ownerId: CUSTOMER1_ID,
      },
      {
        id: '44444444-4444-4444-8444-444444444444',
        accountNumber: 'ACC-MERCHANT-SETTLEMENT-USD',
        accountName: 'Merchant Clearing Account',
        accountType: 'LIABILITY',
        currency: 'USD',
        balance: 48200,
        status: 'ACTIVE',
        createdAt: now,
        updatedAt: now,
        version: 1,
        ownerId: 'b0000000-0000-0000-0000-000000000002',
      },
    ];

    this.entriesByAccount.set('22222222-2222-4222-8222-222222222222', [
      {
        id: 'entry-seed-1',
        transactionId: 'tx-seed-1',
        accountId: '22222222-2222-4222-8222-222222222222',
        accountNumber: 'ACC-CUSTOMER-ALICE-USD',
        entryType: 'CREDIT',
        amount: 15000,
        currency: 'USD',
        balanceAfter: 15000,
        sequenceNumber: 1,
        createdAt: now,
      },
      {
        id: 'entry-seed-2',
        transactionId: 'tx-seed-2',
        accountId: '22222222-2222-4222-8222-222222222222',
        accountNumber: 'ACC-CUSTOMER-ALICE-USD',
        entryType: 'DEBIT',
        amount: 150,
        currency: 'USD',
        balanceAfter: 14850,
        sequenceNumber: 2,
        createdAt: now,
      },
    ]);

    this.auditLogs = [
      {
        id: 'audit-seed-1',
        transactionReference: 'TRF-DEMO-0102',
        sourceAccountId: '22222222-2222-4222-8222-222222222222',
        targetAccountId: '44444444-4444-4444-8444-444444444444',
        amount: 150,
        currency: 'USD',
        riskScore: 0.05,
        riskLevel: 'LOW',
        decision: 'APPROVED',
        reasoning: 'Standard low-value transfer under heuristic threshold ($25,000).',
        engine: 'HEURISTIC',
        modelName: 'Deterministic-Rule-Engine',
        createdAt: now,
      },
      {
        id: 'audit-seed-2',
        transactionReference: 'TRF-DEMO-0101',
        sourceAccountId: '11111111-1111-4111-8111-111111111111',
        targetAccountId: '44444444-4444-4444-8444-444444444444',
        amount: 42500,
        currency: 'USD',
        riskScore: 0.72,
        riskLevel: 'HIGH',
        decision: 'REVIEW_REQUIRED',
        reasoning: 'Elevated notional transfer exceeding $25,000 manual compliance band.',
        engine: 'HEURISTIC',
        modelName: 'Deterministic-Rule-Engine',
        createdAt: now,
      },
    ];

    this.dltMessages = [
      {
        id: 'dlt-demo-001',
        originalTopic: 'nexusfin.ledger.transactions',
        partition: 0,
        offset: 42,
        payload: '{ malformed_poison_pill: true ',
        exceptionClass: 'JsonParseException',
        exceptionMessage: 'Simulated poison-pill quarantine (no retries consumed)',
        quarantinedAt: now,
      },
    ];
  }

  getAccounts(isAdmin: boolean, subject: string): AccountResponse[] {
    if (isAdmin) {
      return structuredClone(this.accounts);
    }
    return structuredClone(this.accounts.filter((a) => a.ownerId === subject));
  }

  createAccount(req: CreateAccountRequest): AccountResponse {
    const duplicate = this.accounts.some((a) => a.accountNumber === req.accountNumber);
    if (duplicate) {
      throw { status: 409, detail: `Account ${req.accountNumber} already exists.` };
    }
    const now = new Date().toISOString();
    const created: AccountResponse = {
      id: this.randomId(),
      accountNumber: req.accountNumber,
      accountName: req.accountName,
      accountType: req.accountType,
      currency: req.currency,
      balance: Number(req.initialBalance),
      status: 'ACTIVE',
      createdAt: now,
      updatedAt: now,
      version: 1,
      ownerId: req.ownerId || null,
    };
    this.accounts = [created, ...this.accounts];
    return structuredClone(created);
  }

  getAccountEntries(accountId: string, isAdmin: boolean, subject: string): LedgerEntryResponse[] {
    const acc = this.accounts.find((a) => a.id === accountId);
    if (!acc) {
      throw { status: 404, detail: `Account ${accountId} not found.` };
    }
    if (!isAdmin && acc.ownerId !== subject) {
      throw { status: 403, detail: 'You do not own this account.' };
    }
    return structuredClone([...(this.entriesByAccount.get(accountId) ?? [])].reverse());
  }

  transferFunds(req: TransferFundsRequest, isAdmin: boolean, subject: string): TransactionResponse {
    const existing = this.transactionsByIdempotency.get(req.idempotencyKey);
    if (existing) {
      return structuredClone(existing);
    }

    const src = this.accounts.find((a) => a.accountNumber === req.sourceAccountNumber);
    const dst = this.accounts.find((a) => a.accountNumber === req.destinationAccountNumber);
    if (!src || !dst) {
      throw { status: 404, detail: 'Source or destination account number was not found.' };
    }
    if (!isAdmin && src.ownerId !== subject) {
      throw { status: 403, detail: 'You can only initiate transfers from your own accounts.' };
    }
    if (src.accountNumber === dst.accountNumber) {
      throw { status: 400, detail: 'Source and destination accounts must differ.' };
    }
    if (req.amount <= 0 || src.balance < req.amount) {
      throw { status: 422, detail: 'Insufficient available balance for transfer.' };
    }

    const now = new Date().toISOString();
    const txId = this.randomId();
    const ref = req.transactionReference || `TRF-${txId.slice(0, 8).toUpperCase()}`;

    src.balance = Number((src.balance - req.amount).toFixed(4));
    src.version += 1;
    src.updatedAt = now;

    dst.balance = Number((dst.balance + req.amount).toFixed(4));
    dst.version += 1;
    dst.updatedAt = now;

    const srcEntries = this.entriesByAccount.get(src.id) ?? [];
    const dstEntries = this.entriesByAccount.get(dst.id) ?? [];

    const debitEntry: LedgerEntryResponse = {
      id: this.randomId(),
      transactionId: txId,
      accountId: src.id,
      accountNumber: src.accountNumber,
      entryType: 'DEBIT',
      amount: req.amount,
      currency: req.currency,
      balanceAfter: src.balance,
      sequenceNumber: srcEntries.length + 1,
      createdAt: now,
    };
    const creditEntry: LedgerEntryResponse = {
      id: this.randomId(),
      transactionId: txId,
      accountId: dst.id,
      accountNumber: dst.accountNumber,
      entryType: 'CREDIT',
      amount: req.amount,
      currency: req.currency,
      balanceAfter: dst.balance,
      sequenceNumber: dstEntries.length + 1,
      createdAt: now,
    };

    srcEntries.push(debitEntry);
    dstEntries.push(creditEntry);
    this.entriesByAccount.set(src.id, srcEntries);
    this.entriesByAccount.set(dst.id, dstEntries);

    const tx: TransactionResponse = {
      id: txId,
      transactionReference: ref,
      idempotencyKey: req.idempotencyKey,
      transactionType: 'TRANSFER',
      status: 'POSTED',
      description: req.description ?? null,
      postedAt: now,
      createdAt: now,
      entries: [debitEntry, creditEntry],
    };
    this.transactionsByIdempotency.set(req.idempotencyKey, tx);

    this.auditTransaction(tx, src, dst, req.amount, req.currency);

    const liveEvt: LiveTransactionEvent = {
      transactionId: tx.id,
      transactionReference: tx.transactionReference,
      transactionType: tx.transactionType,
      totalVolume: req.amount,
      currency: req.currency,
      description: req.description,
      postedAt: now,
      legs: [
        {
          accountId: src.id,
          accountNumber: src.accountNumber,
          entryType: 'DEBIT',
          amount: req.amount,
          currency: req.currency,
          newBalance: src.balance,
          ownerId: src.ownerId ?? undefined,
        },
        {
          accountId: dst.id,
          accountNumber: dst.accountNumber,
          entryType: 'CREDIT',
          amount: req.amount,
          currency: req.currency,
          newBalance: dst.balance,
          ownerId: dst.ownerId ?? undefined,
        },
      ],
    };
    setTimeout(() => this._liveEvents$.next(liveEvt), 120);

    return structuredClone(tx);
  }

  private auditTransaction(
    tx: TransactionResponse,
    src: AccountResponse,
    dst: AccountResponse,
    amount: number,
    currency: string
  ): void {
    let riskScore = 0.05;
    let riskLevel: RiskLevel = 'LOW';
    let decision: FraudDecision = 'APPROVED';
    let reasoning = 'Normal transfer within automated risk thresholds.';

    if (amount >= 1000000) {
      riskScore = 0.95;
      riskLevel = 'CRITICAL';
      decision = 'REJECTED';
      reasoning = 'High-value transfer (>= $1,000,000) flagged by AML policy.';
    } else if (amount >= 25000) {
      riskScore = 0.74;
      riskLevel = 'HIGH';
      decision = 'REVIEW_REQUIRED';
      reasoning = 'Transfer exceeds $25,000 automated approval limit.';
    } else if (amount >= 10000) {
      riskScore = 0.38;
      riskLevel = 'MEDIUM';
      decision = 'APPROVED';
      reasoning = 'Moderate notional amount approved with enhanced telemetry.';
    }

    const log: AuditLogEntry = {
      id: this.randomId(),
      transactionReference: tx.transactionReference,
      sourceAccountId: src.id,
      targetAccountId: dst.id,
      amount,
      currency,
      riskScore,
      riskLevel,
      decision,
      reasoning,
      engine: 'HEURISTIC',
      modelName: 'Sandbox-Heuristic-Pipeline',
      createdAt: tx.postedAt,
    };
    this.auditLogs = [log, ...this.auditLogs].slice(0, 50);
  }

  getRecentAuditLogs(): AuditLogEntry[] {
    return structuredClone(this.auditLogs);
  }

  getDltMessages(): DltMessagesResponse {
    return {
      totalCount: this.dltMessages.length,
      activeQuarantinedCount: this.activeDltCount,
      messages: structuredClone(this.dltMessages),
    };
  }

  replayDltMessage(id: string): { status: string; messageId: string; targetTopic: string } {
    const exists = this.dltMessages.some((m) => m.id === id);
    if (!exists) {
      throw { status: 404, detail: `DLT message ${id} not found.` };
    }
    this.dltMessages = this.dltMessages.filter((m) => m.id !== id);
    this.activeDltCount = Math.max(0, this.activeDltCount - 1);
    return {
      status: 'REPLAYED',
      messageId: id,
      targetTopic: 'nexusfin.ledger.transactions',
    };
  }

  private randomId(): string {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID();
    }
    return `id-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
  }
}
