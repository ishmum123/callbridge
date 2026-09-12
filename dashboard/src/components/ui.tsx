import type { ReactNode } from 'react';
import { Area, AreaChart, ResponsiveContainer } from 'recharts';
import type { CallOutcome, DeviceStatus, FollowUpState, ReviewState, RiskLevel, SyncState } from '../domain/types';
import { fmtDelta } from '../metrics/format';
import { IconClose } from './icons';

// ---------------------------------------------------------------------------
// KPI card
// ---------------------------------------------------------------------------

export type Tone = 'teal' | 'blue' | 'amber' | 'red' | 'purple';

interface KpiProps {
  label: string;
  value: string;
  unit?: string;
  sub?: string;
  delta?: number | null;
  /** Higher-is-worse metrics invert the delta colouring. */
  invert?: boolean;
  tone?: Tone;
  icon: ReactNode;
  spark?: number[];
  testId?: string;
}

const TONE_COLOR: Record<Tone, string> = { teal: '#1abb9c', blue: '#3b82f6', amber: '#f0a92e', red: '#e04b4b', purple: '#8b5cf6' };

export function KpiCard({ label, value, unit, sub, delta, invert, tone = 'teal', icon, spark, testId }: KpiProps) {
  const good = delta !== null && delta !== undefined ? (invert ? delta <= 0 : delta >= 0) : true;
  return (
    <div className="card kpi" data-testid={testId}>
      <div className={`tile ${tone}`}>{icon}</div>
      <div>
        <div className="label">{label}</div>
        <div className="value">
          <span data-testid={testId ? `${testId}-value` : undefined}>{value}</span>
          {unit && <small>{unit}</small>}
          {delta !== undefined && delta !== null && <span className={`delta ${good ? 'up' : 'down'}`}>{fmtDelta(delta)}</span>}
        </div>
        {sub && <div className="sub">{sub}</div>}
      </div>
      {spark && spark.length > 1 && (
        <div className="spark" aria-hidden>
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={spark.map((v, i) => ({ i, v }))} margin={{ top: 2, right: 0, bottom: 0, left: 0 }}>
              <Area type="monotone" dataKey="v" stroke={TONE_COLOR[tone]} fill={TONE_COLOR[tone]} fillOpacity={0.18} strokeWidth={1.5} isAnimationActive={false} />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Panel
// ---------------------------------------------------------------------------

export function Panel({ title, tools, children, flush, aiNote, testId }: { title: ReactNode; tools?: ReactNode; children: ReactNode; flush?: boolean; aiNote?: boolean; testId?: string }) {
  return (
    <section className="card panel" data-testid={testId}>
      <div className="panel-head">
        <h2>{title}</h2>
        {tools && <div className="tools">{tools}</div>}
      </div>
      <div className={`panel-body${flush ? ' flush' : ''}`}>{children}</div>
      {aiNote && <div className="ai-note">AI-extracted from call transcripts. Not a clinical diagnosis; requires clinician review.</div>}
    </section>
  );
}

// ---------------------------------------------------------------------------
// Badges
// ---------------------------------------------------------------------------

export function Chip({ tone, children, onClick, title }: { tone?: 'ok' | 'warn' | 'danger' | 'info' | 'purple'; children: ReactNode; onClick?: () => void; title?: string }) {
  const cls = `chip${tone ? ` ${tone}` : ''}${onClick ? ' btn-chip' : ''}`;
  return onClick ? (
    <button type="button" className={cls} onClick={onClick} title={title}>{children}</button>
  ) : (
    <span className={cls} title={title}>{children}</span>
  );
}

export function OutcomeBadge({ outcome }: { outcome: CallOutcome }) {
  const tone = outcome === 'COMPLETED' ? 'ok' : outcome === 'FAILED' ? 'danger' : 'warn';
  return <Chip tone={tone}>{outcome.replace('_', ' ').toLowerCase()}</Chip>;
}

export function RiskBadge({ risk }: { risk: RiskLevel }) {
  if (risk === 'NONE') return <Chip>none</Chip>;
  const tone = risk === 'HIGH' ? 'danger' : risk === 'MEDIUM' ? 'warn' : 'info';
  return <Chip tone={tone}>{risk.toLowerCase()}</Chip>;
}

export function ReviewBadge({ state }: { state: ReviewState }) {
  const tone = state === 'FLAGGED' ? 'danger' : state === 'REVIEWED' ? 'ok' : undefined;
  return <Chip tone={tone}>{state.toLowerCase()}</Chip>;
}

export function FollowUpBadge({ state, overdue }: { state: FollowUpState; overdue?: boolean }) {
  if (state === 'COMPLETED') return <Chip tone="ok">completed</Chip>;
  if (state === 'REVIEWED') return <Chip tone="info">reviewed</Chip>;
  return <Chip tone={overdue ? 'danger' : 'warn'}>{overdue ? 'overdue' : 'pending'}</Chip>;
}

export function DeviceBadge({ status }: { status: DeviceStatus }) {
  return <Chip tone={status === 'ONLINE' ? 'ok' : 'danger'}>{status.toLowerCase()}</Chip>;
}

export function SyncBadge({ state }: { state: SyncState }) {
  return <Chip tone={state === 'SYNCED' ? 'ok' : state === 'PENDING' ? 'warn' : 'danger'}>{state.toLowerCase()}</Chip>;
}

// ---------------------------------------------------------------------------
// State views
// ---------------------------------------------------------------------------

export function StateView({ status, error, onRetry }: { status: 'loading' | 'empty' | 'unauthorized' | 'error'; error?: string | null; onRetry?: () => void }) {
  if (status === 'loading') {
    return (
      <div className="content" aria-busy="true" aria-live="polite" data-testid="state-loading">
        <span className="sr-only">Loading dashboard data</span>
        <div className="grid kpis">
          {[0, 1, 2, 3].map((i) => (
            <div key={i} className="card kpi">
              <div className="skeleton" style={{ width: 44, height: 44 }} />
              <div style={{ display: 'grid', gap: 8 }}>
                <div className="skeleton" style={{ width: '50%' }} />
                <div className="skeleton" style={{ width: '70%', height: 24 }} />
              </div>
            </div>
          ))}
        </div>
        <div className="card" style={{ height: 280, padding: 16 }}>
          <div className="skeleton" style={{ width: '30%' }} />
        </div>
      </div>
    );
  }
  const copy = {
    empty: { glyph: '🗂️', title: 'No data in this range', body: 'No calls, callers or devices match the current filters or dataset.' },
    unauthorized: { glyph: '🔒', title: 'Admin session required', body: 'Your session is not authorised for the central dashboard. Sign in with an admin account.' },
    error: { glyph: '⚠️', title: 'Could not load dashboard data', body: error ?? 'The data service returned an error.' },
  }[status];
  return (
    <div className="content">
      <div className="card state" role="alert" data-testid={`state-${status}`}>
        <div className="glyph" aria-hidden>{copy.glyph}</div>
        <h3>{copy.title}</h3>
        <p style={{ margin: 0, maxWidth: 420 }}>{copy.body}</p>
        {status === 'unauthorized' ? (
          <button className="btn primary" onClick={onRetry}>Sign in as admin (demo)</button>
        ) : (
          onRetry && <button className="btn" onClick={onRetry}>Retry</button>
        )}
      </div>
    </div>
  );
}

export function EmptyRows({ children = 'Nothing matches the current filters.' }: { children?: ReactNode }) {
  return <div className="state" style={{ padding: 28 }}>{children}</div>;
}

// ---------------------------------------------------------------------------
// Drawer
// ---------------------------------------------------------------------------

export function Drawer({ title, onClose, children, testId }: { title: ReactNode; onClose: () => void; children: ReactNode; testId?: string }) {
  return (
    <>
      <div className="drawer-scrim" onClick={onClose} aria-hidden />
      <aside className="drawer" role="dialog" aria-modal="true" aria-label={typeof title === 'string' ? title : 'Details'} data-testid={testId} onKeyDown={(e) => e.key === 'Escape' && onClose()}>
        <div className="drawer-head">
          <h2>{title}</h2>
          <button className="icon-btn" aria-label="Close" onClick={onClose} autoFocus>
            <IconClose width={18} height={18} />
          </button>
        </div>
        <div className="drawer-body">{children}</div>
      </aside>
    </>
  );
}

export function Toast({ text }: { text: string | null }) {
  return text ? <div className="toast" role="status">{text}</div> : null;
}
