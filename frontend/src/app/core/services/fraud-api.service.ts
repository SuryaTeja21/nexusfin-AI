import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { getApiBaseUrl } from '../auth/auth.config';
import { AuditLogEntry, DltMessagesResponse } from '../models/nexusfin.models';

@Injectable({ providedIn: 'root' })
export class FraudApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = getApiBaseUrl();

  getRecentAuditLogs(): Observable<AuditLogEntry[]> {
    return this.http.get<AuditLogEntry[]>(`${this.baseUrl}/api/v1/fraud/audit-logs`);
  }

  getDltMessages(): Observable<DltMessagesResponse> {
    return this.http.get<DltMessagesResponse>(`${this.baseUrl}/api/v1/fraud/dlt/messages`);
  }

  replayDltMessage(id: string): Observable<{ status: string; messageId: string; targetTopic: string }> {
    return this.http.post<{ status: string; messageId: string; targetTopic: string }>(
      `${this.baseUrl}/api/v1/fraud/dlt/replay/${encodeURIComponent(id)}`,
      {}
    );
  }
}
