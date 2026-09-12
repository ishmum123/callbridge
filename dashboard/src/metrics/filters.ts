import { type AgeGroup, type Call, type CallDirection, type CallOutcome, type Caller, type Device, type FollowUp, type Observation, type PatientProfile, type ProfileUpdate, type RiskLevel, type Sex, type Shop, type Snapshot, type Turn, ageGroupOf } from '../domain/types';

export type RangePreset = '7d' | '30d' | '90d';
export const RANGE_PRESETS: RangePreset[] = ['7d', '30d', '90d'];
const DAY_MS = 86_400_000;

export interface Filters {
  /** Inclusive start, exclusive end (ms). */
  from: number;
  to: number;
  shopIds: string[];
  district?: string;
  upazila?: string;
  village?: string;
  ageGroups?: AgeGroup[];
  sexes?: Sex[];
  directions?: CallDirection[];
  outcomes?: CallOutcome[];
  risks?: RiskLevel[];
  /** Observation category filter (calls having at least one matching observation). */
  categories?: string[];
  /** Free text over caller number, name, village, call id. */
  search?: string;
}

export function rangeFromPreset(preset: RangePreset, referenceDate: number): { from: number; to: number } {
  const days = preset === '7d' ? 7 : preset === '30d' ? 30 : 90;
  return { from: referenceDate - days * DAY_MS, to: referenceDate + DAY_MS };
}

export function defaultFilters(referenceDate: number, preset: RangePreset = '30d'): Filters {
  return { ...rangeFromPreset(preset, referenceDate), shopIds: [] };
}

/** Snapshot restricted to the filter. Records keep their original shape. */
export interface FilteredView {
  snapshot: Snapshot;
  filters: Filters;
  shops: Shop[];
  devices: Device[];
  calls: Call[];
  callers: Caller[];
  profiles: PatientProfile[];
  profileByNumber: Map<string, PatientProfile>;
  callerByNumber: Map<string, Caller>;
  shopById: Map<string, Shop>;
  observations: Observation[];
  followUps: FollowUp[];
  turnsByCall: Map<string, Turn[]>;
  updatesByNumber: Map<string, ProfileUpdate[]>;
}

const has = <T>(arr: T[] | undefined) => !!arr && arr.length > 0;

export function applyFilters(snapshot: Snapshot, filters: Filters): FilteredView {
  const shopById = new Map(snapshot.shops.map((s) => [s.id, s]));
  const callerByNumber = new Map(snapshot.callers.map((c) => [c.number, c]));
  const profileByNumber = new Map(snapshot.profiles.map((p) => [p.number, p]));

  // Location filters narrow the eligible shop set (village also matches caller village).
  let shops = snapshot.shops;
  if (has(filters.shopIds)) shops = shops.filter((s) => filters.shopIds.includes(s.id));
  if (filters.district) shops = shops.filter((s) => s.district === filters.district);
  if (filters.upazila) shops = shops.filter((s) => s.upazila === filters.upazila);
  const shopSet = new Set(shops.map((s) => s.id));

  const obsCategoriesByCall = new Map<string, Set<string>>();
  if (has(filters.categories)) {
    for (const o of snapshot.observations) {
      if (!obsCategoriesByCall.has(o.callId)) obsCategoriesByCall.set(o.callId, new Set());
      obsCategoriesByCall.get(o.callId)!.add(o.category);
    }
  }
  const q = filters.search?.trim().toLowerCase();

  const callMatches = (c: Call): boolean => {
    if (c.startedAt < filters.from || c.startedAt >= filters.to) return false;
    if (!shopSet.has(c.shopId)) return false;
    if (has(filters.directions) && !filters.directions!.includes(c.direction)) return false;
    if (has(filters.outcomes) && !filters.outcomes!.includes(c.outcome)) return false;
    if (has(filters.risks) && !filters.risks!.includes(c.risk)) return false;
    const caller = callerByNumber.get(c.number);
    const profile = profileByNumber.get(c.number);
    if (filters.village && caller?.village !== filters.village && shopById.get(c.shopId)?.village !== filters.village) return false;
    if (has(filters.ageGroups) && !filters.ageGroups!.includes(ageGroupOf(profile?.ageYears ?? null))) return false;
    if (has(filters.sexes) && !filters.sexes!.includes(profile?.sex ?? 'UNKNOWN')) return false;
    if (has(filters.categories)) {
      const cats = obsCategoriesByCall.get(c.id);
      if (!cats || !filters.categories!.some((k) => cats.has(k))) return false;
    }
    if (q) {
      const hay = `${c.id} ${c.number} ${caller?.name ?? ''} ${caller?.village ?? ''} ${shopById.get(c.shopId)?.name ?? ''}`.toLowerCase();
      if (!hay.includes(q)) return false;
    }
    return true;
  };

  const calls = snapshot.calls.filter(callMatches);
  const callIds = new Set(calls.map((c) => c.id));
  const numbers = new Set(calls.map((c) => c.number));

  const callers = snapshot.callers.filter((c) => numbers.has(c.number));
  const profiles = snapshot.profiles.filter((p) => numbers.has(p.number));
  const observations = snapshot.observations.filter((o) => callIds.has(o.callId));
  const followUps = snapshot.followUps.filter((f) => callIds.has(f.callId));
  const devices = snapshot.devices.filter((d) => d.shopId !== null && shopSet.has(d.shopId));

  const turnsByCall = new Map<string, Turn[]>();
  for (const t of snapshot.turns) {
    if (!callIds.has(t.callId)) continue;
    turnsByCall.set(t.callId, [...(turnsByCall.get(t.callId) ?? []), t]);
  }
  const updatesByNumber = new Map<string, ProfileUpdate[]>();
  for (const u of snapshot.profileUpdates) {
    if (!numbers.has(u.number)) continue;
    updatesByNumber.set(u.number, [...(updatesByNumber.get(u.number) ?? []), u]);
  }

  return { snapshot, filters, shops, devices, calls, callers, profiles, profileByNumber, callerByNumber, shopById, observations, followUps, turnsByCall, updatesByNumber };
}
