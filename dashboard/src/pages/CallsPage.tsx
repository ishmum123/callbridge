import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { usePage } from '../app/PageContext';
import { CallDetail } from '../components/CallDetail';
import { DataTable, type Column } from '../components/DataTable';
import { Chip, OutcomeBadge, Panel, ReviewBadge, RiskBadge } from '../components/ui';
import { HEALTH_PACK, type Call, type CallDirection, type CallOutcome, type RiskLevel } from '../domain/types';
import { applyFilters } from '../metrics/filters';
import { fmtDateTime, fmtDuration, fmtMs, fmtUsd } from '../metrics/format';
import { durationSec } from '../metrics/kpis';

export function CallsPage() {
  const { view, snapshot, toast } = usePage();
  const [sp, setSp] = useSearchParams();
  const params = useParams<{ id?: string }>();
  const nav = useNavigate();

  const q = sp.get('q') ?? '';
  const outcome = sp.get('outcome') ?? '';
  const direction = sp.get('direction') ?? '';
  const risk = sp.get('risk') ?? '';
  const category = sp.get('category') ?? '';
  const caller = sp.get('caller') ?? '';
  const day = sp.get('day') ?? '';
  const setP = (k: string, v: string) =>
    setSp((prev) => {
      const p = new URLSearchParams(prev);
      v ? p.set(k, v) : p.delete(k);
      return p;
    }, { replace: true });

  const local = useMemo(() => {
    const f = { ...view.filters, search: q || undefined };
    if (outcome) f.outcomes = [outcome as CallOutcome];
    if (direction) f.directions = [direction as CallDirection];
    if (risk) f.risks = [risk as RiskLevel];
    if (category) f.categories = [category];
    if (day) {
      const from = Date.parse(`${day}T00:00:00Z`);
      f.from = Math.max(f.from, from);
      f.to = Math.min(f.to, from + 86_400_000);
    }
    let v = applyFilters(snapshot, f);
    if (caller) v = { ...v, calls: v.calls.filter((c) => c.number === caller) };
    return v;
  }, [snapshot, view.filters, q, outcome, direction, risk, category, caller, day]);

  const rows = useMemo(() => [...local.calls].sort((a, b) => b.startedAt - a.startedAt), [local]);
  const open = params.id ? snapshot.calls.find((c) => c.id === params.id) ?? null : null;
  const [search, setSearch] = useState(q);
  useEffect(() => setSearch(q), [q]);

  const cols: Column<Call>[] = [
    { key: 'id', header: 'Call', cell: (c) => <span className="mono">{c.id}</span>, sort: (c) => c.id },
    { key: 'when', header: 'Started', cell: (c) => fmtDateTime(c.startedAt), sort: (c) => c.startedAt, csv: (c) => new Date(c.startedAt).toISOString() },
    { key: 'caller', header: 'Caller', cell: (c) => <><Link to={`/callers/${c.number}`} onClick={(e) => e.stopPropagation()}>{view.callerByNumber.get(c.number)?.name ?? 'Unregistered'}</Link> <span className="mono" style={{ color: 'var(--muted)' }}>{c.number}</span></>, sort: (c) => view.callerByNumber.get(c.number)?.name ?? c.number, csv: (c) => c.number },
    { key: 'shop', header: 'Shop', cell: (c) => view.shopById.get(c.shopId)?.name ?? c.shopId, sort: (c) => view.shopById.get(c.shopId)?.name ?? c.shopId },
    { key: 'dir', header: 'Direction', cell: (c) => <Chip>{c.direction === 'INBOUND' ? 'inbound' : 'callback'}</Chip>, sort: (c) => c.direction, csv: (c) => c.direction },
    { key: 'outcome', header: 'Outcome', cell: (c) => <OutcomeBadge outcome={c.outcome} />, sort: (c) => c.outcome, csv: (c) => c.outcome },
    { key: 'risk', header: 'Risk', cell: (c) => <RiskBadge risk={c.risk} />, sort: (c) => ['NONE', 'LOW', 'MEDIUM', 'HIGH'].indexOf(c.risk), csv: (c) => c.risk },
    { key: 'dur', header: 'Duration', cell: (c) => fmtDuration(durationSec(c)), sort: (c) => durationSec(c), num: true, csv: (c) => Math.round(durationSec(c)) },
    { key: 'lat', header: 'Latency', cell: (c) => fmtMs(c.latencyMs), sort: (c) => c.latencyMs, num: true, csv: (c) => c.latencyMs },
    { key: 'cost', header: 'Cost', cell: (c) => fmtUsd(c.estCostUsd, 4), sort: (c) => c.estCostUsd, num: true, csv: (c) => c.estCostUsd },
    { key: 'review', header: 'Review', cell: (c) => <ReviewBadge state={c.reviewState} />, sort: (c) => c.reviewState, csv: (c) => c.reviewState },
  ];

  return (
    <>
      <div className="page-head">
        <div>
          <div className="eyebrow">Monitor</div>
          <h1>Calls</h1>
        </div>
      </div>

      <Panel
        title={<>Call history <span style={{ color: 'var(--muted)', fontWeight: 400 }}>· {rows.length} calls</span></>}
        flush
        tools={
          <form
            className="filter-bar"
            onSubmit={(e) => {
              e.preventDefault();
              setP('q', search);
            }}
          >
            <label>
              <span className="sr-only">Search</span>
              <input className="input" style={{ width: 200 }} placeholder="Search id, number, name…" value={search} onChange={(e) => setSearch(e.target.value)} data-testid="calls-search" />
            </label>
            <label>
              <span className="sr-only">Direction</span>
              <select className="select" value={direction} onChange={(e) => setP('direction', e.target.value)} aria-label="Direction">
                <option value="">All directions</option>
                <option value="INBOUND">Inbound</option>
                <option value="OUTBOUND_CALLBACK">Callback</option>
              </select>
            </label>
            <label>
              <span className="sr-only">Outcome</span>
              <select className="select" value={outcome} onChange={(e) => setP('outcome', e.target.value)} aria-label="Outcome" data-testid="calls-outcome">
                <option value="">All outcomes</option>
                {(['COMPLETED', 'FAILED', 'NO_ANSWER', 'HUNG_UP'] as CallOutcome[]).map((o) => <option key={o} value={o}>{o.replace('_', ' ')}</option>)}
              </select>
            </label>
            <label>
              <span className="sr-only">Risk</span>
              <select className="select" value={risk} onChange={(e) => setP('risk', e.target.value)} aria-label="Risk level">
                <option value="">All risk levels</option>
                {(['HIGH', 'MEDIUM', 'LOW', 'NONE'] as RiskLevel[]).map((r) => <option key={r} value={r}>{r}</option>)}
              </select>
            </label>
            <label>
              <span className="sr-only">Domain category</span>
              <select className="select" value={category} onChange={(e) => setP('category', e.target.value)} aria-label="Domain category">
                <option value="">All categories</option>
                {HEALTH_PACK.categories.map((c) => <option key={c.key} value={c.key}>{c.title}</option>)}
              </select>
            </label>
            <label>
              <span className="sr-only">Day</span>
              <input className="input" type="date" style={{ width: 150 }} value={day} onChange={(e) => setP('day', e.target.value)} aria-label="Day" />
            </label>
            {caller && <Chip tone="info" onClick={() => setP('caller', '')}>caller {caller} ✕</Chip>}
            <button className="btn sm" type="submit">Apply</button>
            {(q || outcome || direction || risk || category || caller || day) && (
              <button className="btn sm" type="button" onClick={() => setSp(new URLSearchParams(), { replace: true })}>Clear</button>
            )}
          </form>
        }
      >
        <DataTable rows={rows} columns={cols} rowKey={(c) => c.id} pageSize={20} exportName="calls" caption="Call history" onRowClick={(c) => nav(`/calls/${c.id}${window.location.search}`)} testId="calls-table" />
      </Panel>

      {open && <CallDetail call={open} snapshot={snapshot} onClose={() => nav(`/calls${window.location.search}`)} onToast={toast} />}
    </>
  );
}
