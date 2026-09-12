import { type AgeGroup, type Call, type Caller, type MetricPack, type Observation, type RiskLevel, ageGroupOf } from '../domain/types';
import type { FilteredView } from './filters';
import { isoDay } from './kpis';

const DAY_MS = 86_400_000;

/** A single aggregate bucket. `ids` are the contributing record ids for drill-down. */
export interface Bucket {
  key: string;
  label: string;
  count: number;
  /** Contributing call ids (or caller numbers for caller-based buckets). */
  ids: string[];
}

export interface DomainMetrics {
  pack: MetricPack;
  callers: { total: number; new: number; repeat: number; callsPerCaller: number };
  reach: { byShop: Bucket[]; byVillage: Bucket[]; byAgeGroup: Bucket[]; bySex: Bucket[] };
  /** Top codes per pack category, keyed by category key. */
  categories: Record<string, Bucket[]>;
  risk: { byLevel: Bucket[]; trend: { date: string; medium: number; high: number }[] };
  followUps: { pending: number; overdue: number; completed: number; reviewed: number; byShop: Bucket[] };
  quality: {
    missingProfileRate: number;
    transcriptCoverage: number;
    unclassifiedObservations: number;
    summarizationFailures: number;
    completedCalls: number;
  };
}

function bucketize<T>(items: T[], keyOf: (t: T) => string, idOf: (t: T) => string, labelOf: (k: string) => string = (k) => k): Bucket[] {
  const m = new Map<string, Set<string>>();
  for (const it of items) {
    const k = keyOf(it);
    if (!m.has(k)) m.set(k, new Set());
    m.get(k)!.add(idOf(it));
  }
  return [...m.entries()]
    .map(([key, ids]) => ({ key, label: labelOf(key), count: ids.size, ids: [...ids] }))
    .sort((a, b) => b.count - a.count || a.key.localeCompare(b.key));
}

export function domainMetrics(view: FilteredView, pack: MetricPack): DomainMetrics {
  const { calls, observations, followUps, profileByNumber, callerByNumber, shopById, filters } = view;
  const completed = calls.filter((c) => c.outcome === 'COMPLETED');
  const numbers = new Set(calls.map((c) => c.number));

  // A caller is "new" if their first call ever falls inside the window.
  const firstCall = new Map<string, number>();
  for (const c of view.snapshot.calls) {
    const f = firstCall.get(c.number);
    if (f === undefined || c.startedAt < f) firstCall.set(c.number, c.startedAt);
  }
  let newCallers = 0;
  for (const n of numbers) {
    const f = firstCall.get(n)!;
    if (f >= filters.from && f < filters.to) newCallers++;
  }

  const ageOf = (c: Call): AgeGroup => ageGroupOf(profileByNumber.get(c.number)?.ageYears ?? null);
  const sexOf = (c: Call) => profileByNumber.get(c.number)?.sex ?? 'UNKNOWN';
  const villageOf = (c: Call) => callerByNumber.get(c.number)?.village ?? shopById.get(c.shopId)?.village ?? 'Unknown';

  const packObs = observations.filter((o) => o.pack === pack.pack);
  const categories: Record<string, Bucket[]> = {};
  for (const cat of pack.categories) {
    categories[cat.key] = bucketize(
      packObs.filter((o) => o.category === cat.key),
      (o) => o.code,
      (o) => o.callId,
      (k) => packObs.find((o) => o.code === k)?.label ?? k,
    );
  }

  const riskCalls = completed.filter((c) => c.risk !== 'NONE');
  const trendMap = new Map<string, { date: string; medium: number; high: number }>();
  for (let t = filters.from; t < filters.to; t += DAY_MS) trendMap.set(isoDay(t), { date: isoDay(t), medium: 0, high: 0 });
  for (const c of riskCalls) {
    const p = trendMap.get(isoDay(c.startedAt));
    if (!p) continue;
    if (c.risk === 'MEDIUM') p.medium++;
    if (c.risk === 'HIGH') p.high++;
  }

  const overdue = followUps.filter((f) => f.state === 'PENDING' && f.dueAt < view.snapshot.referenceDate);
  const withProfile = [...numbers].filter((n) => profileByNumber.has(n)).length;

  return {
    pack,
    callers: {
      total: numbers.size,
      new: newCallers,
      repeat: numbers.size - newCallers,
      callsPerCaller: numbers.size ? calls.length / numbers.size : 0,
    },
    reach: {
      byShop: bucketize(calls, (c) => c.shopId, (c) => c.number, (k) => shopById.get(k)?.name ?? k),
      byVillage: bucketize(calls, villageOf, (c) => c.number),
      byAgeGroup: bucketize(calls, ageOf, (c) => c.number),
      bySex: bucketize(calls, sexOf, (c) => c.number, (k) => ({ F: 'Female', M: 'Male', UNKNOWN: 'Unknown' })[k] ?? k),
    },
    categories,
    risk: {
      byLevel: (['HIGH', 'MEDIUM', 'LOW', 'NONE'] as RiskLevel[]).map((lvl) => {
        const ids = completed.filter((c) => c.risk === lvl).map((c) => c.id);
        return { key: lvl, label: lvl, count: ids.length, ids };
      }),
      trend: [...trendMap.values()],
    },
    followUps: {
      pending: followUps.filter((f) => f.state === 'PENDING').length,
      overdue: overdue.length,
      completed: followUps.filter((f) => f.state === 'COMPLETED').length,
      reviewed: followUps.filter((f) => f.state === 'REVIEWED').length,
      byShop: bucketize(followUps.filter((f) => f.state === 'PENDING'), (f) => f.shopId, (f) => f.callId, (k) => shopById.get(k)?.name ?? k),
    },
    quality: {
      missingProfileRate: numbers.size ? 1 - withProfile / numbers.size : 0,
      transcriptCoverage: completed.length ? completed.filter((c) => c.hasTranscript).length / completed.length : 0,
      unclassifiedObservations: observations.filter((o) => o.category === 'unclassified').length,
      summarizationFailures: completed.filter((c) => c.summarizationError).length,
      completedCalls: completed.length,
    },
  };
}

/** Records behind a bucket, for drill-down panels. */
export interface Drilldown {
  calls: Call[];
  callers: Caller[];
  observations: Observation[];
}

export function drilldown(view: FilteredView, bucket: Bucket): Drilldown {
  const ids = new Set(bucket.ids);
  const calls = view.calls.filter((c) => ids.has(c.id) || ids.has(c.number));
  const numbers = new Set(calls.map((c) => c.number));
  return {
    calls,
    callers: view.callers.filter((c) => numbers.has(c.number)),
    observations: view.observations.filter((o) => ids.has(o.callId)),
  };
}

/** Minimum cohort size below which aggregate rows are hidden in exports. */
export const MIN_COHORT = 5;

export function suppressSmallCohorts<T extends { count: number }>(rows: T[], min = MIN_COHORT): { rows: T[]; suppressed: number } {
  const kept = rows.filter((r) => r.count >= min);
  return { rows: kept, suppressed: rows.length - kept.length };
}
