import type { Call, Shop } from '../domain/types';
import type { FilteredView } from './filters';

const DAY_MS = 86_400_000;

export interface OverviewKpis {
  calls: number;
  uniqueCallers: number;
  avgDurationSec: number;
  estCostUsd: number;
  costPerCallUsd: number;
  failedRate: number;
  pendingFollowUps: number;
  devicesOnline: number;
  devicesOffline: number;
}

export const durationSec = (c: Call): number => (c.endedAt ? (c.endedAt - c.startedAt) / 1000 : 0);

export function overviewKpis(view: FilteredView): OverviewKpis {
  const { calls, devices, followUps } = view;
  const completed = calls.filter((c) => c.outcome === 'COMPLETED');
  const failed = calls.filter((c) => c.outcome === 'FAILED');
  const cost = calls.reduce((a, c) => a + c.estCostUsd, 0);
  return {
    calls: calls.length,
    uniqueCallers: new Set(calls.map((c) => c.number)).size,
    avgDurationSec: completed.length ? completed.reduce((a, c) => a + durationSec(c), 0) / completed.length : 0,
    estCostUsd: cost,
    costPerCallUsd: calls.length ? cost / calls.length : 0,
    failedRate: calls.length ? failed.length / calls.length : 0,
    pendingFollowUps: followUps.filter((f) => f.state === 'PENDING').length,
    devicesOnline: devices.filter((d) => d.status === 'ONLINE').length,
    devicesOffline: devices.filter((d) => d.status === 'OFFLINE').length,
  };
}

export interface DailyPoint {
  /** ISO date yyyy-mm-dd (UTC). */
  date: string;
  calls: number;
  completed: number;
  failed: number;
  costUsd: number;
}

export const isoDay = (ms: number): string => new Date(ms).toISOString().slice(0, 10);

/** One point per day across the filter range, zero-filled. */
export function dailySeries(view: FilteredView): DailyPoint[] {
  const { from, to } = view.filters;
  const byDay = new Map<string, DailyPoint>();
  for (let t = from; t < to; t += DAY_MS) {
    const d = isoDay(t);
    byDay.set(d, { date: d, calls: 0, completed: 0, failed: 0, costUsd: 0 });
  }
  for (const c of view.calls) {
    const p = byDay.get(isoDay(c.startedAt));
    if (!p) continue;
    p.calls++;
    if (c.outcome === 'COMPLETED') p.completed++;
    if (c.outcome === 'FAILED') p.failed++;
    p.costUsd += c.estCostUsd;
  }
  return [...byDay.values()];
}

export interface ShopRow {
  shop: Shop;
  calls: number;
  callers: number;
  failedRate: number;
  costUsd: number;
  avgLatencyMs: number;
  pendingFollowUps: number;
  devicesOnline: number;
  devicesTotal: number;
}

export function shopComparison(view: FilteredView): ShopRow[] {
  return view.shops
    .map((shop) => {
      const calls = view.calls.filter((c) => c.shopId === shop.id);
      const devices = view.devices.filter((d) => d.shopId === shop.id);
      return {
        shop,
        calls: calls.length,
        callers: new Set(calls.map((c) => c.number)).size,
        failedRate: calls.length ? calls.filter((c) => c.outcome === 'FAILED').length / calls.length : 0,
        costUsd: calls.reduce((a, c) => a + c.estCostUsd, 0),
        avgLatencyMs: calls.length ? calls.reduce((a, c) => a + c.latencyMs, 0) / calls.length : 0,
        pendingFollowUps: view.followUps.filter((f) => f.shopId === shop.id && f.state === 'PENDING').length,
        devicesOnline: devices.filter((d) => d.status === 'ONLINE').length,
        devicesTotal: devices.length,
      };
    })
    .sort((a, b) => b.calls - a.calls);
}

/** Percentage change of the current window vs the previous window of equal length. */
export function deltaVsPrevious(view: FilteredView, pick: (c: Call) => number): number | null {
  const { from, to } = view.filters;
  const len = to - from;
  const prev = view.snapshot.calls.filter((c) => c.startedAt >= from - len && c.startedAt < from && view.shops.some((s) => s.id === c.shopId));
  const cur = view.calls.reduce((a, c) => a + pick(c), 0);
  const before = prev.reduce((a, c) => a + pick(c), 0);
  if (!before) return null;
  return (cur - before) / before;
}
