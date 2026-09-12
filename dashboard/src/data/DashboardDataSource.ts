import type { AuditEntry, Call, Device, FollowUp, FollowUpState, ReviewState, Shop, Shopkeeper, Snapshot } from '../domain/types';

/**
 * Boundary between the UI and data access. The mock implementation and a
 * future HTTP implementation both satisfy this. Everything is asynchronous
 * and returns plain records; pages never reach past it.
 */
export interface DashboardDataSource {
  /** Full dataset. Pages filter client-side via metrics/. */
  getSnapshot(): Promise<Snapshot>;

  addFollowUpNote(id: string, text: string): Promise<FollowUp>;
  setFollowUpState(id: string, state: FollowUpState): Promise<FollowUp>;
  setCallReview(callId: string, state: ReviewState, note?: string): Promise<Call>;

  upsertShop(shop: Shop): Promise<Shop>;
  upsertShopkeeper(sk: Shopkeeper): Promise<Shopkeeper>;
  assignDevice(deviceId: string, shopId: string | null): Promise<Device>;

  recordExport(target: string): Promise<AuditEntry>;
  getAudit(): Promise<AuditEntry[]>;
}

export class UnauthorizedError extends Error {
  readonly status = 401;
  constructor(message = 'Admin session required') {
    super(message);
    this.name = 'UnauthorizedError';
  }
}

export class ServerError extends Error {
  readonly status: number;
  constructor(message = 'Upstream service unavailable', status = 503) {
    super(message);
    this.name = 'ServerError';
    this.status = status;
  }
}
