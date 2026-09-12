export const fmtInt = (n: number): string => new Intl.NumberFormat('en-US').format(Math.round(n));
export const fmtUsd = (n: number, digits = 2): string => `$${n.toFixed(digits)}`;
export const fmtPct = (n: number, digits = 1): string => `${(n * 100).toFixed(digits)}%`;
export const fmtMs = (n: number): string => `${Math.round(n)} ms`;

export function fmtDuration(sec: number): string {
  if (!sec) return '0s';
  const m = Math.floor(sec / 60);
  const s = Math.round(sec % 60);
  return m ? `${m}m ${s.toString().padStart(2, '0')}s` : `${s}s`;
}

export function fmtDate(ms: number | null | undefined): string {
  if (!ms) return '—';
  return new Date(ms).toISOString().slice(0, 10);
}

export function fmtDateTime(ms: number | null | undefined): string {
  if (!ms) return '—';
  const d = new Date(ms);
  return `${d.toISOString().slice(0, 10)} ${d.toISOString().slice(11, 16)}Z`;
}

/** Relative to the dataset reference date, not the wall clock, so the demo is stable. */
export function fmtAgo(ms: number | null | undefined, now: number): string {
  if (!ms) return 'never';
  const diff = now - ms;
  if (diff < 0) return 'just now';
  const min = Math.round(diff / 60_000);
  if (min < 60) return `${min} min ago`;
  const h = Math.round(min / 60);
  if (h < 48) return `${h} h ago`;
  return `${Math.round(h / 24)} d ago`;
}

export function fmtDelta(d: number | null): string {
  if (d === null) return '';
  const s = (d * 100).toFixed(0);
  return d >= 0 ? `+${s}%` : `${s}%`;
}

export function maskNumber(n: string): string {
  return n.length > 6 ? `${n.slice(0, 3)}••••${n.slice(-3)}` : n;
}
