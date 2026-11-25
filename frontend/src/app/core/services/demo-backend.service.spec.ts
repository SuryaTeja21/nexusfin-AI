import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { DemoBackendService } from './demo-backend.service';

describe('DemoBackendService', () => {
  let service: DemoBackendService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [DemoBackendService] });
    service = TestBed.inject(DemoBackendService);
  });

  it('enforces tenant account isolation for CUSTOMER vs ADMIN', () => {
    const adminAccounts = service.getAccounts(true, 'a0000000-0000-0000-0000-000000000001');
    const custAccounts = service.getAccounts(false, 'c0000000-0000-0000-0000-000000000001');

    expect(adminAccounts.length).toBeGreaterThan(custAccounts.length);
    expect(custAccounts.every((a) => a.ownerId === 'c0000000-0000-0000-0000-000000000001')).toBe(true);
  });

  it('executes double-entry transfer idempotently, updates fraud audit logs, and emits live event', async () => {
    const livePromise = firstValueFrom(service.liveEvents$);
    const idempKey = '00000000-0000-4000-8000-000000000001';

    const tx1 = service.transferFunds(
      {
        sourceAccountNumber: 'ACC-ASSET-CENTRAL-RESERVE-USD',
        destinationAccountNumber: 'ACC-CUSTOMER-ALICE-USD',
        amount: 30000,
        currency: 'USD',
        idempotencyKey: idempKey,
        description: 'Sandbox test settlement',
      },
      true,
      'admin-sub'
    );

    const tx2 = service.transferFunds(
      {
        sourceAccountNumber: 'ACC-ASSET-CENTRAL-RESERVE-USD',
        destinationAccountNumber: 'ACC-CUSTOMER-ALICE-USD',
        amount: 30000,
        currency: 'USD',
        idempotencyKey: idempKey,
      },
      true,
      'admin-sub'
    );

    expect(tx1.id).toBe(tx2.id);
    expect(tx1.entries.length).toBe(2);

    const latestAudit = service.getRecentAuditLogs()[0];
    expect(latestAudit.transactionReference).toBe(tx1.transactionReference);
    expect(latestAudit.riskLevel).toBe('HIGH');
    expect(latestAudit.decision).toBe('REVIEW_REQUIRED');

    const liveEvent = await livePromise;
    expect(liveEvent.transactionReference).toBe(tx1.transactionReference);
  });
});
