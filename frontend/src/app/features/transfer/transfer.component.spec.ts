import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ToastService } from '../../core/services/toast.service';
import { TransferComponent } from './transfer.component';

describe('TransferComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TransferComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), ToastService],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('initializes with a valid UUID idempotency key and enforces distinct accounts', () => {
    const fixture = TestBed.createComponent(TransferComponent);
    fixture.detectChanges();

    const accountsReq = httpMock.expectOne('/api/v1/accounts');
    accountsReq.flush([
      {
        id: 'acc-1',
        accountNumber: 'ACC-1001',
        accountName: 'Checking',
        accountType: 'ASSET',
        currency: 'USD',
        balance: 5000,
        status: 'ACTIVE',
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
        version: 1,
        ownerId: 'customer1',
      },
    ]);

    const component = fixture.componentInstance;
    const key = component.transferForm.controls.idempotencyKey.value;
    expect(key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);

    component.transferForm.patchValue({
      sourceAccountNumber: 'ACC-1001',
      destinationAccountNumber: 'ACC-1001',
      amount: 250,
    });
    expect(component.transferForm.invalid).toBe(true);
    expect(component.transferForm.errors?.['sameAccount']).toBe(true);
  });

  it('posts transfer request with idempotency key and renders execution receipt', () => {
    const fixture = TestBed.createComponent(TransferComponent);
    fixture.detectChanges();
    httpMock.expectOne('/api/v1/accounts').flush([]);

    const component = fixture.componentInstance;
    const initialKey = component.transferForm.controls.idempotencyKey.value;

    component.transferForm.patchValue({
      sourceAccountNumber: 'ACC-1001',
      destinationAccountNumber: 'ACC-1002',
      amount: 175.5,
      currency: 'USD',
      description: 'Vendor settlement',
    });

    component.submitTransfer();

    const postReq = httpMock.expectOne('/api/v1/ledger/transfers');
    expect(postReq.request.method).toBe('POST');
    expect(postReq.request.body).toEqual({
      sourceAccountNumber: 'ACC-1001',
      destinationAccountNumber: 'ACC-1002',
      amount: 175.5,
      currency: 'USD',
      idempotencyKey: initialKey,
      description: 'Vendor settlement',
    });

    postReq.flush({
      id: 'tx-123',
      transactionReference: 'TXN-9991',
      idempotencyKey: initialKey,
      transactionType: 'TRANSFER',
      status: 'POSTED',
      description: 'Vendor settlement',
      postedAt: '2026-01-01T12:00:00Z',
      createdAt: '2026-01-01T12:00:00Z',
      entries: [
        {
          id: 'e-1',
          transactionId: 'tx-123',
          accountId: 'a-1',
          accountNumber: 'ACC-1001',
          entryType: 'DEBIT',
          amount: 175.5,
          currency: 'USD',
          balanceAfter: 4824.5,
          sequenceNumber: 1,
          createdAt: '2026-01-01T12:00:00Z',
        },
        {
          id: 'e-2',
          transactionId: 'tx-123',
          accountId: 'a-2',
          accountNumber: 'ACC-1002',
          entryType: 'CREDIT',
          amount: 175.5,
          currency: 'USD',
          balanceAfter: 175.5,
          sequenceNumber: 2,
          createdAt: '2026-01-01T12:00:00Z',
        },
      ],
    });

    // Reloads accounts after transfer
    httpMock.expectOne('/api/v1/accounts').flush([]);

    fixture.detectChanges();
    const receipt = (fixture.nativeElement as HTMLElement).querySelector('[data-testid="transfer-receipt"]');
    expect(receipt?.textContent).toContain('TXN-9991');
    expect(component.transferForm.controls.idempotencyKey.value).not.toBe(initialKey);
  });
});
