import { useMemo } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { usePage } from '../app/PageContext';
import { DataTable, type Column } from '../components/DataTable';
import { Chip, DeviceBadge, Panel, SyncBadge } from '../components/ui';
import type { Device } from '../domain/types';
import { fmtAgo, fmtInt, fmtPct, fmtUsd } from '../metrics/format';
import { shopComparison, type ShopRow } from '../metrics/kpis';

export function ShopsPage() {
  const { view, snapshot } = usePage();
  const [sp, setSp] = useSearchParams();
  const selected = sp.get('shop') ?? '';
  const rows = useMemo(() => shopComparison(view), [view]);
  const devices = useMemo(() => snapshot.devices.filter((d) => !selected || d.shopId === selected), [snapshot, selected]);
  const skName = (id: string | null) => snapshot.shopkeepers.find((s) => s.id === id)?.name ?? '—';

  const shopCols: Column<ShopRow>[] = [
    { key: 'name', header: 'Shop', cell: (r) => <strong>{r.shop.name}</strong>, sort: (r) => r.shop.name },
    { key: 'loc', header: 'Location', cell: (r) => `${r.shop.village}, ${r.shop.upazila}, ${r.shop.district}`, sort: (r) => r.shop.district },
    { key: 'sk', header: 'Shopkeeper', cell: (r) => skName(r.shop.shopkeeperId), sort: (r) => skName(r.shop.shopkeeperId) },
    { key: 'calls', header: 'Calls', cell: (r) => fmtInt(r.calls), sort: (r) => r.calls, num: true },
    { key: 'failed', header: 'Failed', cell: (r) => fmtPct(r.failedRate), sort: (r) => r.failedRate, num: true },
    { key: 'cost', header: 'Cost', cell: (r) => fmtUsd(r.costUsd), sort: (r) => r.costUsd, num: true },
    { key: 'fu', header: 'Follow-ups', cell: (r) => fmtInt(r.pendingFollowUps), sort: (r) => r.pendingFollowUps, num: true },
    { key: 'dev', header: 'Devices', cell: (r) => <Chip tone={r.devicesOnline === r.devicesTotal ? 'ok' : 'danger'}>{r.devicesOnline}/{r.devicesTotal} online</Chip>, sort: (r) => r.devicesOnline / Math.max(1, r.devicesTotal), csv: (r) => `${r.devicesOnline}/${r.devicesTotal}` },
  ];
  const devCols: Column<Device>[] = [
    { key: 'label', header: 'Device', cell: (d) => <><strong>{d.label}</strong> <span className="mono" style={{ color: 'var(--muted)' }}>{d.id}</span></>, sort: (d) => d.label, csv: (d) => d.id },
    { key: 'shop', header: 'Shop', cell: (d) => view.shopById.get(d.shopId ?? '')?.name ?? <em>unassigned</em>, sort: (d) => view.shopById.get(d.shopId ?? '')?.name ?? '' },
    { key: 'status', header: 'Status', cell: (d) => <DeviceBadge status={d.status} />, sort: (d) => d.status, csv: (d) => d.status },
    { key: 'seen', header: 'Last seen', cell: (d) => fmtAgo(d.lastSeenAt, snapshot.referenceDate), sort: (d) => d.lastSeenAt, csv: (d) => new Date(d.lastSeenAt).toISOString() },
    { key: 'call', header: 'Last call', cell: (d) => fmtAgo(d.lastCallAt, snapshot.referenceDate), sort: (d) => d.lastCallAt ?? 0, csv: (d) => (d.lastCallAt ? new Date(d.lastCallAt).toISOString() : '') },
    { key: 'ver', header: 'App', cell: (d) => <span className="mono">{d.appVersion}</span>, sort: (d) => d.appVersion },
    { key: 'sim', header: 'SIM', cell: (d) => <span className="mono">{d.simId}</span>, sort: (d) => d.simId },
    { key: 'sync', header: 'Sync', cell: (d) => <SyncBadge state={d.syncState} />, sort: (d) => d.syncState, csv: (d) => d.syncState },
    { key: 'err', header: 'Recent errors', cell: (d) => (d.recentErrors.length ? <div className="chips">{d.recentErrors.map((e) => <Chip key={e} tone="danger">{e}</Chip>)}</div> : <span style={{ color: 'var(--muted)' }}>none</span>), sort: (d) => d.recentErrors.length, csv: (d) => d.recentErrors.join('; ') },
  ];

  return (
    <>
      <div className="page-head">
        <div><div className="eyebrow">Operate</div><h1>Shops &amp; devices</h1></div>
        <div className="actions">
          {selected && <button className="btn sm" onClick={() => setSp(new URLSearchParams(), { replace: true })}>Clear shop selection</button>}
          <Link to="/admin" className="btn">Manage in Administration</Link>
        </div>
      </div>
      <Panel title="Shop directory" flush testId="shops-table">
        <DataTable rows={rows} columns={shopCols} rowKey={(r) => r.shop.id} pageSize={10} initialSort={{ key: 'calls', dir: 'desc' }} exportName="shops" caption="Shop directory" onRowClick={(r) => setSp({ shop: r.shop.id }, { replace: true })} />
      </Panel>
      <Panel title={<>Device status {selected && <span style={{ color: 'var(--muted)', fontWeight: 400 }}>· {view.shopById.get(selected)?.name}</span>}</>} flush testId="devices-table">
        <DataTable rows={devices} columns={devCols} rowKey={(d) => d.id} pageSize={12} initialSort={{ key: 'status', dir: 'desc' }} exportName="devices" caption="Device status" />
      </Panel>
    </>
  );
}
