import { useState } from 'react';
import { usePage } from '../app/PageContext';
import { useDataSource } from '../app/DataSourceProvider';
import { downloadCsv } from '../components/DataTable';
import { Chip, Panel } from '../components/ui';
import { fmtDateTime } from '../metrics/format';
import { toCsv } from '../metrics/table';

export function AdminPage() {
  const { snapshot, toast } = usePage();
  const { mutate } = useDataSource();
  const [shopName, setShopName] = useState('');
  const [district, setDistrict] = useState('Rangpur');
  const [skName, setSkName] = useState('');
  const [skPhone, setSkPhone] = useState('');
  const [skShop, setSkShop] = useState('');
  const [devId, setDevId] = useState(snapshot.devices[0]?.id ?? '');
  const [devShop, setDevShop] = useState('');

  const addShop = async () => {
    const id = `shop-${String(snapshot.shops.length + 1).padStart(2, '0')}`;
    await mutate((s) => s.upsertShop({ id, orgId: snapshot.organization.id, name: shopName, district, upazila: '—', village: '—', shopkeeperId: null, createdAt: Date.now() }));
    setShopName(''); toast(`Shop ${id} created (in-memory)`);
  };
  const addSk = async () => {
    const id = `sk-${String(snapshot.shopkeepers.length + 1).padStart(2, '0')}`;
    await mutate((s) => s.upsertShopkeeper({ id, name: skName, phone: skPhone, shopId: skShop || null, active: true }));
    setSkName(''); setSkPhone(''); toast(`Shopkeeper ${id} created (in-memory)`);
  };
  const assign = async () => { await mutate((s) => s.assignDevice(devId, devShop || null)); toast(`Device ${devId} reassigned`); };
  const exportAll = async (what: 'calls' | 'callers') => {
    const csv = what === 'calls'
      ? toCsv(snapshot.calls, [{ header: 'id', value: (c) => c.id }, { header: 'startedAt', value: (c) => new Date(c.startedAt).toISOString() }, { header: 'shop', value: (c) => c.shopId }, { header: 'outcome', value: (c) => c.outcome }, { header: 'costUsd', value: (c) => c.estCostUsd }])
      : toCsv(snapshot.callers, [{ header: 'number', value: (c) => c.number }, { header: 'name', value: (c) => c.name }, { header: 'village', value: (c) => c.village }, { header: 'shop', value: (c) => c.registeredByShop }]);
    downloadCsv(csv, `${what}-full.csv`);
    await mutate((s) => s.recordExport(`${what} (full)`));
    toast(`Exported ${what}`);
  };

  return (
    <>
      <div className="page-head"><div><div className="eyebrow">Admin</div><h1>Administration</h1></div><Chip tone="warn">Mock management — no real auth, changes reset on reload</Chip></div>
      <div className="grid three">
        <Panel title="Shops">
          <ul className="list-plain" style={{ marginBottom: 12 }}>{snapshot.shops.map((s) => <li key={s.id} style={{ fontSize: 13 }}><strong>{s.name}</strong> <span style={{ color: 'var(--muted)' }}>· {s.district} · {snapshot.shopkeepers.find((k) => k.id === s.shopkeeperId)?.name ?? 'no shopkeeper'}</span></li>)}</ul>
          <div className="form">
            <label className="field"><span>New shop name</span><input className="input" value={shopName} onChange={(e) => setShopName(e.target.value)} /></label>
            <label className="field"><span>District</span><select className="select" value={district} onChange={(e) => setDistrict(e.target.value)}>{[...new Set(snapshot.shops.map((s) => s.district))].map((d) => <option key={d}>{d}</option>)}</select></label>
            <div className="form-actions"><button className="btn primary" disabled={!shopName.trim()} onClick={addShop}>Create shop</button></div>
          </div>
        </Panel>
        <Panel title="Shopkeeper accounts">
          <ul className="list-plain" style={{ marginBottom: 12 }}>{snapshot.shopkeepers.map((k) => <li key={k.id} style={{ fontSize: 13 }}><strong>{k.name}</strong> <span className="mono" style={{ color: 'var(--muted)' }}>{k.phone}</span> · {snapshot.shops.find((s) => s.id === k.shopId)?.name ?? 'unassigned'} {k.active ? <Chip tone="ok">active</Chip> : <Chip>inactive</Chip>}</li>)}</ul>
          <div className="form">
            <label className="field"><span>Name</span><input className="input" value={skName} onChange={(e) => setSkName(e.target.value)} /></label>
            <label className="field"><span>Phone</span><input className="input" value={skPhone} onChange={(e) => setSkPhone(e.target.value)} /></label>
            <label className="field"><span>Shop</span><select className="select" value={skShop} onChange={(e) => setSkShop(e.target.value)}><option value="">Unassigned</option>{snapshot.shops.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label>
            <div className="form-actions"><button className="btn primary" disabled={!skName.trim()} onClick={addSk}>Create account</button></div>
          </div>
        </Panel>
        <Panel title="Device assignment">
          <div className="form">
            <label className="field"><span>Device</span><select className="select" value={devId} onChange={(e) => setDevId(e.target.value)}>{snapshot.devices.map((d) => <option key={d.id} value={d.id}>{d.label} ({d.id}) — {snapshot.shops.find((s) => s.id === d.shopId)?.name ?? 'unassigned'}</option>)}</select></label>
            <label className="field"><span>Assign to shop</span><select className="select" value={devShop} onChange={(e) => setDevShop(e.target.value)}><option value="">Unassigned</option>{snapshot.shops.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label>
            <div className="form-actions"><button className="btn primary" onClick={assign}>Assign</button></div>
          </div>
        </Panel>
      </div>
      <div className="grid two">
        <Panel title="Audit history" flush>
          <div className="table-wrap"><table className="data"><thead><tr><th>When</th><th>Actor</th><th>Action</th><th>Target</th></tr></thead><tbody>
            {snapshot.audit.map((a) => <tr key={a.id}><td>{fmtDateTime(a.at)}</td><td>{a.actor}</td><td><span className="mono">{a.action}</span></td><td>{a.target}</td></tr>)}
          </tbody></table></div>
        </Panel>
        <Panel title="Exports">
          <p style={{ marginTop: 0, color: 'var(--muted)', fontSize: 13 }}>Full-record exports of demo data. Aggregate exports on the Health insights page suppress small cohorts.</p>
          <div className="form-actions" style={{ justifyContent: 'flex-start' }}>
            <button className="btn" onClick={() => exportAll('calls')}>Export calls CSV</button>
            <button className="btn" onClick={() => exportAll('callers')}>Export callers CSV</button>
          </div>
        </Panel>
      </div>
    </>
  );
}
