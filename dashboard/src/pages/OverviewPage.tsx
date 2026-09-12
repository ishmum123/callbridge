import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { usePage } from '../app/PageContext';
import { CallDetail } from '../components/CallDetail';
import { TrendChart } from '../components/charts';
import { DataTable, type Column } from '../components/DataTable';
import { IconAlert, IconClock, IconDevice, IconDollar, IconFlag, IconPhone, IconUsers } from '../components/icons';
import { Chip, KpiCard, OutcomeBadge, Panel, RiskBadge } from '../components/ui';
import type { Call } from '../domain/types';
import { fmtDate, fmtDateTime, fmtDuration, fmtInt, fmtMs, fmtPct, fmtUsd } from '../metrics/format';
import { dailySeries, deltaVsPrevious, overviewKpis, shopComparison, type ShopRow } from '../metrics/kpis';

export function OverviewPage() {
  const { view, snapshot, toast } = usePage();
  const nav = useNavigate();
  const [openCall, setOpenCall] = useState<Call | null>(null);

  const k = useMemo(() => overviewKpis(view), [view]);
  const series = useMemo(() => dailySeries(view), [view]);
  const shops = useMemo(() => shopComparison(view), [view]);
  const dCalls = useMemo(() => deltaVsPrevious(view, () => 1), [view]);
  const dCost = useMemo(() => deltaVsPrevious(view, (c) => c.estCostUsd), [view]);
  const dFailed = useMemo(() => deltaVsPrevious(view, (c) => (c.outcome === 'FAILED' ? 1 : 0)), [view]);

  const recentFailures = useMemo(() => [...view.calls].filter((c) => c.outcome === 'FAILED').sort((a, b) => b.startedAt - a.startedAt).slice(0, 8), [view]);
  const urgent = useMemo(() => {
    const ref = snapshot.referenceDate;
    return [...view.followUps]
      .filter((f) => f.state === 'PENDING')
      .sort((a, b) => (b.risk === 'HIGH' ? 1 : 0) - (a.risk === 'HIGH' ? 1 : 0) || a.dueAt - b.dueAt)
      .slice(0, 8)
      .map((f) => ({ ...f, overdue: f.dueAt < ref }));
  }, [view, snapshot.referenceDate]);

  const shopCols: Column<ShopRow>[] = [
    { key: 'shop', header: 'Shop', cell: (r) => <Link to={`/shops?shop=${r.shop.id}`}>{r.shop.name}</Link>, sort: (r) => r.shop.name },
    { key: 'loc', header: 'Location', cell: (r) => `${r.shop.upazila}, ${r.shop.district}`, sort: (r) => r.shop.district },
    { key: 'calls', header: 'Calls', cell: (r) => fmtInt(r.calls), sort: (r) => r.calls, num: true },
    { key: 'callers', header: 'Callers', cell: (r) => fmtInt(r.callers), sort: (r) => r.callers, num: true },
    { key: 'failed', header: 'Failed', cell: (r) => fmtPct(r.failedRate), sort: (r) => r.failedRate, num: true },
    { key: 'cost', header: 'Cost', cell: (r) => fmtUsd(r.costUsd), sort: (r) => r.costUsd, num: true },
    { key: 'lat', header: 'Latency', cell: (r) => fmtMs(r.avgLatencyMs), sort: (r) => r.avgLatencyMs, num: true },
    { key: 'fu', header: 'Follow-ups', cell: (r) => fmtInt(r.pendingFollowUps), sort: (r) => r.pendingFollowUps, num: true },
    { key: 'dev', header: 'Devices', cell: (r) => <Chip tone={r.devicesOnline === r.devicesTotal ? 'ok' : 'danger'}>{r.devicesOnline}/{r.devicesTotal} online</Chip>, sort: (r) => r.devicesOnline / Math.max(1, r.devicesTotal), csv: (r) => `${r.devicesOnline}/${r.devicesTotal}` },
  ];

  const spark = (pick: (p: (typeof series)[number]) => number) => series.slice(-14).map(pick);

  return (
    <>
      <div className="page-head">
        <div>
          <div className="eyebrow">Overview</div>
          <h1>Dashboard</h1>
        </div>
        <div className="actions">
          <span style={{ color: 'var(--muted)', fontSize: 12, alignSelf: 'center' }}>
            {fmtDate(view.filters.from)} → {fmtDate(view.filters.to - 1)} · reference date {fmtDate(snapshot.referenceDate)}
          </span>
        </div>
      </div>

      <div className="grid kpis">
        <KpiCard testId="kpi-calls" label="Calls" value={fmtInt(k.calls)} delta={dCalls} sub={`${fmtInt(k.uniqueCallers)} unique callers`} icon={<IconPhone />} tone="teal" spark={spark((p) => p.calls)} />
        <KpiCard testId="kpi-callers" label="Unique callers" value={fmtInt(k.uniqueCallers)} sub={`${(k.calls / Math.max(1, k.uniqueCallers)).toFixed(2)} calls per caller`} icon={<IconUsers />} tone="blue" />
        <KpiCard testId="kpi-duration" label="Avg duration" value={fmtDuration(k.avgDurationSec)} sub="completed calls" icon={<IconClock />} tone="purple" />
        <KpiCard testId="kpi-cost" label="Estimated cost" value={fmtUsd(k.estCostUsd)} delta={dCost} invert sub={`${fmtUsd(k.costPerCallUsd, 4)} per call`} icon={<IconDollar />} tone="amber" spark={spark((p) => p.costUsd)} />
        <KpiCard testId="kpi-failed" label="Failed-call rate" value={fmtPct(k.failedRate)} delta={dFailed} invert sub="of all attempted calls" icon={<IconAlert />} tone="red" spark={spark((p) => p.failed)} />
        <KpiCard testId="kpi-followups" label="Pending follow-ups" value={fmtInt(k.pendingFollowUps)} sub="across filtered shops" icon={<IconFlag />} tone="amber" />
        <KpiCard testId="kpi-devices" label="Devices online" value={`${k.devicesOnline}`} unit={`/ ${k.devicesOnline + k.devicesOffline}`} sub={k.devicesOffline ? `${k.devicesOffline} offline` : 'all healthy'} icon={<IconDevice />} tone={k.devicesOffline ? 'red' : 'teal'} />
        <KpiCard testId="kpi-cpc" label="Cost per call" value={fmtUsd(k.costPerCallUsd, 3)} sub="Gemini Live audio estimate" icon={<IconDollar />} tone="blue" />
      </div>

      <div className="grid two">
        <Panel title="Daily calls and cost" testId="trend-panel">
          <TrendChart
            data={series}
            series={[
              { key: 'calls', name: 'Calls', color: '#1abb9c' },
              { key: 'failed', name: 'Failed', color: '#e04b4b' },
              { key: 'costUsd', name: 'Cost (USD)', color: '#f0a92e', axis: 'right' },
            ]}
          />
        </Panel>
        <Panel title="Urgent follow-ups" tools={<Link to="/follow-ups" className="btn sm">View queue</Link>} flush>
          {urgent.length === 0 && <div className="state" style={{ padding: 24 }}>No pending follow-ups.</div>}
          <ul className="list-plain" style={{ padding: '0 16px' }}>
            {urgent.map((f) => {
              const caller = view.callerByNumber.get(f.number);
              return (
                <li key={f.id} className="feed-item">
                  <RiskBadge risk={f.risk} />
                  <div style={{ minWidth: 0 }}>
                    <div>
                      <Link to={`/follow-ups?id=${f.id}`}>{caller?.name ?? f.number}</Link> · {view.shopById.get(f.shopId)?.name}
                    </div>
                    <div className="when">{f.reason} · due {fmtDate(f.dueAt)}{f.overdue ? ' · overdue' : ''}</div>
                  </div>
                </li>
              );
            })}
          </ul>
        </Panel>
      </div>

      <div className="grid two">
        <Panel title="Shop comparison" flush testId="shop-comparison">
          <DataTable rows={shops} columns={shopCols} rowKey={(r) => r.shop.id} pageSize={10} initialSort={{ key: 'calls', dir: 'desc' }} exportName="shop-comparison" caption="Shop comparison" onRowClick={(r) => nav(`/shops?shop=${r.shop.id}`)} />
        </Panel>
        <Panel title="Recent failures" tools={<Link to="/calls?outcome=FAILED" className="btn sm">All failures</Link>} flush>
          {recentFailures.length === 0 && <div className="state" style={{ padding: 24 }}>No failed calls in range.</div>}
          <ul className="list-plain" style={{ padding: '0 16px' }}>
            {recentFailures.map((c) => (
              <li key={c.id} className="feed-item" style={{ cursor: 'pointer' }} onClick={() => setOpenCall(c)}>
                <OutcomeBadge outcome={c.outcome} />
                <div style={{ minWidth: 0 }}>
                  <div>
                    <span className="mono">{c.id}</span> · {view.shopById.get(c.shopId)?.name} · <span className="mono">{c.endReason}</span>
                  </div>
                  <div className="when">{fmtDateTime(c.startedAt)} · device {c.deviceId}</div>
                </div>
              </li>
            ))}
          </ul>
        </Panel>
      </div>

      {openCall && <CallDetail call={openCall} snapshot={snapshot} onClose={() => setOpenCall(null)} onToast={toast} />}
    </>
  );
}
