import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { getApiBaseUrl } from '../auth/auth.config';
import {
  AccountResponse,
  CreateAccountRequest,
  LedgerEntryResponse,
  TransactionResponse,
  TransferFundsRequest,
} from '../models/nexusfin.models';

@Injectable({ providedIn: 'root' })
export class LedgerApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = getApiBaseUrl();

  getAccounts(): Observable<AccountResponse[]> {
    return this.http.get<AccountResponse[]>(`${this.baseUrl}/api/v1/accounts`);
  }

  createAccount(request: CreateAccountRequest): Observable<AccountResponse> {
    return this.http.post<AccountResponse>(`${this.baseUrl}/api/v1/accounts`, request);
  }

  getAccountEntries(accountId: string): Observable<LedgerEntryResponse[]> {
    return this.http.get<LedgerEntryResponse[]>(`${this.baseUrl}/api/v1/accounts/${accountId}/entries`);
  }

  transferFunds(request: TransferFundsRequest): Observable<TransactionResponse> {
    return this.http.post<TransactionResponse>(`${this.baseUrl}/api/v1/ledger/transfers`, request);
  }
}
