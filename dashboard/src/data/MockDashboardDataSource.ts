import type { AuditEntry, Call, Device, FollowUp, FollowUpState, ReviewState, Shop, Shopkeeper, Snapshot } from '../domain/types';
import { type DashboardDataSource, ServerError, UnauthorizedError } from './DashboardDataSource';
import { DEFAULT_SEED, REFERENCE_DATE, generateSnapshot, type GeneratorOptions } from './generator';

/** Simulated backend condition. Switchable at runtime from the Demo controls. */
export type DemoMode = 'ok' | 'loading' | 'empty' | 'unauthorized' | 'error';
export const DEMO_MODES: DemoMode[] = ['ok', 'loading', 'empty', 'unauthorized', 'error'];

export interface MockOptions extends GeneratorOptions {
  /** Artificial latency per request, ms. 0 in tests. */
  latencyMs?: number;
  mode?: DemoMode;
  /** Who mutations are attributed to in the audit log. */
  actor?: string;
}

const sleep = (ms: number) => (ms > 0 ? new Promise<void>((r) => setTimeout(r, ms)) : Promise.resolve());

/**
 * In-memory data source over a deterministic fixture. Mutations change the
 * in-memory arrays and append audit entries; a page reload resets everything.
 */
export class MockDashboardDataSource implements DashboardDataSource {
  private snapshot: Snapshot;
  private readonly latencyMs: number;
  private readonly actor: string;
  mode: DemoMode;

  constructor(opts: MockOptions = {}) {
    this.snapshot = generateSnapshot({ seed: opts.seed ?? DEFAULT_SEED, referenceDate: opts.referenceDate ?? REFERENCE_DATE, ...opts });
    this.latencyMs = opts.latencyMs ?? 350;
    this.mode = opts.mode ?? 'ok';
    this.actor = opts.actor ?? 'admin';
  }

  private async gate(): Promise<void> {
    await sleep(this.latencyMs);
    if (this.mode === 'loading') {
      // Never resolves: simulates a hung backend so the loading state stays visible.
      await new Promise<never>(() => {});
    }
    if (this.mode === 'unauthorized') throw new UnauthorizedError();
    if (this.mode === 'error') throw new ServerError();
  }

  private audit(action: string, target: string): AuditEntry {
    const entry: AuditEntry = {
      id: `aud-${String(this.snapshot.audit.length + 1).padStart(4, '0')}`,
      at: Date.now(),
      actor: this.actor,
      action,
      target,
    };
    this.snapshot = { ...this.snapshot, audit: [entry, ...this.snapshot.audit] };
    return entry;
  }

  async getSnapshot(): Promise<Snapshot> {
    await this.gate();
    if (this.mode === 'empty') {
      return {
        ...this.snapshot,
        shops: [], shopkeepers: [], devices: [], callers: [], calls: [], turns: [],
        profiles: [], profileUpdates: [], observations: [], followUps: [], audit: [],
      };
    }
    return this.snapshot;
  }

  async addFollowUpNote(id: string, text: string): Promise<FollowUp> {
    await this.gate();
    const fu = this.snapshot.followUps.find((f) => f.id === id);
    if (!fu) throw new ServerError(`Follow-up ${id} not found`, 404);
    const updated: FollowUp = { ...fu, notes: [...fu.notes, { at: Date.now(), actor: this.actor, text }] };
    this.snapshot = { ...this.snapshot, followUps: this.snapshot.followUps.map((f) => (f.id === id ? updated : f)) };
    this.audit('FOLLOWUP_NOTE', id);
    return updated;
  }

  async setFollowUpState(id: string, state: FollowUpState): Promise<FollowUp> {
    await this.gate();
    const fu = this.snapshot.followUps.find((f) => f.id === id);
    if (!fu) throw new ServerError(`Follow-up ${id} not found`, 404);
    const updated: FollowUp = { ...fu, state };
    this.snapshot = { ...this.snapshot, followUps: this.snapshot.followUps.map((f) => (f.id === id ? updated : f)) };
    this.audit(`FOLLOWUP_${state}`, id);
    return updated;
  }

  async setCallReview(callId: string, state: ReviewState, note?: string): Promise<Call> {
    await this.gate();
    const call = this.snapshot.calls.find((c) => c.id === callId);
    if (!call) throw new ServerError(`Call ${callId} not found`, 404);
    const updated: Call = { ...call, reviewState: state, adminNote: note ?? call.adminNote };
    this.snapshot = { ...this.snapshot, calls: this.snapshot.calls.map((c) => (c.id === callId ? updated : c)) };
    this.audit(`CALL_${state}`, callId);
    return updated;
  }

  async upsertShop(shop: Shop): Promise<Shop> {
    await this.gate();
    const exists = this.snapshot.shops.some((s) => s.id === shop.id);
    this.snapshot = {
      ...this.snapshot,
      shops: exists ? this.snapshot.shops.map((s) => (s.id === shop.id ? shop : s)) : [...this.snapshot.shops, shop],
    };
    this.audit(exists ? 'UPDATE_SHOP' : 'CREATE_SHOP', shop.id);
    return shop;
  }

  async upsertShopkeeper(sk: Shopkeeper): Promise<Shopkeeper> {
    await this.gate();
    const exists = this.snapshot.shopkeepers.some((s) => s.id === sk.id);
    this.snapshot = {
      ...this.snapshot,
      shopkeepers: exists ? this.snapshot.shopkeepers.map((s) => (s.id === sk.id ? sk : s)) : [...this.snapshot.shopkeepers, sk],
      shops: this.snapshot.shops.map((s) => (s.id === sk.shopId ? { ...s, shopkeeperId: sk.id } : s.shopkeeperId === sk.id && s.id !== sk.shopId ? { ...s, shopkeeperId: null } : s)),
    };
    this.audit(exists ? 'UPDATE_SHOPKEEPER' : 'CREATE_SHOPKEEPER', sk.id);
    return sk;
  }

  async assignDevice(deviceId: string, shopId: string | null): Promise<Device> {
    await this.gate();
    const dev = this.snapshot.devices.find((d) => d.id === deviceId);
    if (!dev) throw new ServerError(`Device ${deviceId} not found`, 404);
    const updated: Device = { ...dev, shopId };
    this.snapshot = { ...this.snapshot, devices: this.snapshot.devices.map((d) => (d.id === deviceId ? updated : d)) };
    this.audit('ASSIGN_DEVICE', `${deviceId} → ${shopId ?? 'unassigned'}`);
    return updated;
  }

  async recordExport(target: string): Promise<AuditEntry> {
    await this.gate();
    return this.audit('EXPORT_CSV', target);
  }

  async getAudit(): Promise<AuditEntry[]> {
    await this.gate();
    return this.snapshot.audit;
  }
}
