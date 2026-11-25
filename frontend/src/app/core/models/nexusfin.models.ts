export type AccountType = 'ASSET' | 'LIABILITY' | 'EQUITY' | 'REVENUE' | 'EXPENSE';
export type AccountStatus = 'ACTIVE' | 'FROZEN' | 'CLOSED';
export type EntryType = 'DEBIT' | 'CREDIT';
export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type FraudDecision = 'APPROVED' | 'REVIEW_REQUIRED' | 'REJECTED';

export interface AccountResponse {
  id: string;
  accountNumber: string;
  accountName: string;
  accountType: AccountType;
  currency: string;
  balance: number;
  status: AccountStatus;
  createdAt: string;
  updatedAt: string;
  version: number;
  ownerId: string | null;
}

export interface CreateAccountRequest {
  accountNumber: string;
  accountName: string;
  accountType: AccountType;
  currency: string;
  initialBalance: number;
  ownerId?: string | null;
}

export interface LedgerEntryResponse {
  id: string;
  transactionId: string;
  accountId: string;
  accountNumber: string;
  entryType: EntryType;
  amount: number;
  currency: string;
  balanceAfter: number;
  sequenceNumber: number;
  createdAt: string;
}

export interface TransactionResponse {
  id: string;
  transactionReference: string;
  idempotencyKey: string;
  transactionType: string;
  status: string;
  description: string | null;
  postedAt: string;
  createdAt: string;
  entries: LedgerEntryResponse[];
}

export interface TransferFundsRequest {
  sourceAccountNumber: string;
  destinationAccountNumber: string;
  amount: number;
  currency: string;
  idempotencyKey: string;
  transactionReference?: string;
  description?: string;
}

export interface TransactionEventLeg {
  accountId?: string;
  accountNumber: string;
  accountType?: string;
  entryType: EntryType;
  amount: number;
  currency: string;
  newBalance?: number;
  ownerId?: string;
}

export interface LiveTransactionEvent {
  transactionId: string;
  transactionReference: string;
  transactionType?: string;
  totalVolume?: number;
  amount?: number;
  currency?: string;
  description?: string;
  postedAt?: string;
  legs?: TransactionEventLeg[];
}

export interface AuditLogEntry {
  id: string;
  transactionReference: string;
  sourceAccountId: string;
  targetAccountId: string;
  amount: number;
  currency: string;
  riskScore: number;
  riskLevel: RiskLevel;
  decision: FraudDecision;
  reasoning: string | null;
  engine: string | null;
  modelName: string | null;
  createdAt: string;
}

export interface DltMessage {
  id: string;
  originalTopic: string;
  partition: number;
  offset: number;
  payload: string;
  exceptionClass: string | null;
  exceptionMessage: string | null;
  quarantinedAt: string;
}

export interface DltMessagesResponse {
  totalCount: number;
  activeQuarantinedCount: number;
  messages: DltMessage[];
}

export interface AppUser {
  username: string;
  email: string;
  subject: string;
  roles: string[];
}
