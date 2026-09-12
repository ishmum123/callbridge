import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { usePage } from '../app/PageContext';
import { useDataSource } from '../app/DataSourceProvider';
import { CallDetail } from '../components/CallDetail';
import { DataTable, type Column } from '../components/DataTable';
import { Drawer, FollowUpBadge, Panel, RiskBadge } from '../components/ui';
import type { Call, FollowUp } from '../domain/types';
import { fmtDate, fmtDateTime } from '../metrics/format';

const RISK_ORDER = { HIGH: 0, MEDIUM: 1, LOW: 2, NONE: 3 };

export function FollowUpsPage() {
  const { view, snapshot, toast } = usePage();
  const { mutate } = useDataSource();
  const [sp, setSp] = useSearchParams();
  const [state, setState] = useState<'PENDING' | 'ALL'>('PENDING');
  const [note, setNote] = useState('');
  const [openCall, setOpenCall] = useState<Call | null>(null);
  const ref = snapshot.referenceDate;

  const rows = useMemo(
    () =>
      [...view.followUps]
        .filter((f) => state === 'ALL' || f.state === 'PENDING')
        .sort((a, b) => (a.state === 'PENDING' ? 0 : 1) - (b.state === 'PENDING' ? 0 : 1) || RISK_ORDER[a.risk] - RISK_ORDER[b.risk] || a.dueAt - b.dueAt),
    [view, state],
  );
  const open = sp.get('id') ? snapshot.followUps.find((f) => f.id === sp.get('id')) ?? null : null;
  const close = () => setSp((p) => { const n = new URLSearchParams(p); n.delete('id'); return n; }, { replace: true });

  const cols: Column<FollowUp>[] = [
    { key: 'risk', header: 'Risk', cell: (f) => <RiskBadge risk={f.risk} />, sort: (f) => RISK_ORDER[f.risk], csv: (f) => f.risk },
    { key: 'state', header: 'Due', cell: (f) => <><FollowUpBadge state={f.state} overdue={f.state === 'PENDING' && f.dueAt < ref} /> <span style={{ color: 'var(--muted)' }}>{fmtDate(f.dueAt)}</span></>, sort: (f) => f.dueAt, csv: (f) => `${f.state} ${fmtDate(f.dueAt)}` },
    { key: 'caller', header: 'Caller', cell: (f) => <Link to={`/callers/${f.number}`} onClick={(e) => e.stopPropagation()}>{view.callerByNumber.get(f.number)?.name ?? f.number}</Link>, sort: (f) => view.callerByNumber.get(f.number)?.name ?? f.number, csv: (f) => f.number },
    { key: 'shop', header: 'Shop', cell: (f) => view.shopById.get(f.shopId)?.name ?? f.shopId, sort: (f) => view.shopById.get(f.shopId)?.name ?? '' },
    { key: 'call', header: 'Latest call', cell: (f) => <span className="mono">{f.callId}</span>, sort: (f) => f.createdAt, csv: (f) => f.callId },
    { key: 'reason', header: 'Reason', cell: (f) => f.reason, sort: (f) => f.reason },
    { key: 'notes', header: 'Notes', cell: (f) => f.notes.length, sort: (f) => f.notes.length, num: true },
  ];

  const act = async (fn: () => Promise<unknown>, msg: string) => { await fn(); toast(msg); };

  return (
    <>
      <div className="page-head">
        <div><div className="eyebrow">Operate</div><h1>Follow-ups</h1></div>
        <div className="seg" role="group" aria-label="Queue filter">
          <button aria-pressed={state === 'PENDING'} onClick={() => setState('PENDING')}>Pending</button>
          <button aria-pressed={state === 'ALL'} onClick={() => setState('ALL')}>All</button>
        </div>
      </div>
      <Panel title={<>Prioritised queue <span style={{ color: 'var(--muted)', fontWeight: 400 }}>· {rows.length}</span></>} flush>
        <DataTable rows={rows} columns={cols} rowKey={(f) => f.id} pageSize={20} exportName="follow-ups" caption="Follow-up queue" onRowClick={(f) => setSp((p) => { const n = new URLSearchParams(p); n.set('id', f.id); return n; }, { replace: true })} testId="followups-table" />
      </Panel>

      {open && (
        <Drawer title={`Follow-up ${open.id}`} onClose={close} testId="followup-detail">
          <div className="chips"><RiskBadge risk={open.risk} /><FollowUpBadge state={open.state} overdue={open.state === 'PENDING' && open.dueAt < ref} /></div>
          <dl className="kv">
            <dt>Caller</dt><dd><Link to={`/callers/${open.number}`}>{view.callerByNumber.get(open.number)?.name ?? open.number}</Link></dd>
            <dt>Shop</dt><dd>{view.shopById.get(open.shopId)?.name}</dd>
            <dt>Reason</dt><dd>{open.reason}</dd>
            <dt>Raised</dt><dd>{fmtDate(open.createdAt)} from <button className="btn sm" onClick={() => setOpenCall(snapshot.calls.find((c) => c.id === open.callId) ?? null)}>{open.callId}</button></dd>
            <dt>Due</dt><dd>{fmtDate(open.dueAt)}</dd>
          </dl>
          <div>
            <div className="section-title">Notes</div>
            <ul className="list-plain">
              {open.notes.length === 0 && <li style={{ color: 'var(--muted)' }}>No notes yet.</li>}
              {open.notes.map((n, i) => <li key={i} className="feed-item"><div><div>{n.text}</div><div className="when">{n.actor} · {fmtDateTime(n.at)}</div></div></li>)}
            </ul>
          </div>
          <label className="field"><span>Add note</span><textarea className="input" rows={3} value={note} onChange={(e) => setNote(e.target.value)} data-testid="fu-note" /></label>
          <div className="form-actions">
            <button className="btn" disabled={!note.trim()} onClick={() => act(() => mutate((s) => s.addFollowUpNote(open.id, note.trim())).then(() => setNote('')), 'Note added')} data-testid="fu-add-note">Add note</button>
            <button className="btn" disabled={open.state !== 'PENDING'} onClick={() => act(() => mutate((s) => s.setFollowUpState(open.id, 'REVIEWED')), 'Marked reviewed')}>Mark reviewed</button>
            <button className="btn primary" disabled={open.state === 'COMPLETED'} onClick={() => act(() => mutate((s) => s.setFollowUpState(open.id, 'COMPLETED')), 'Marked completed')} data-testid="fu-complete">Mark completed</button>
          </div>
          <p style={{ fontSize: 12, color: 'var(--muted)', margin: 0 }}>Changes are in-memory demo actions and reset on reload.</p>
        </Drawer>
      )}
      {openCall && <CallDetail call={openCall} snapshot={snapshot} onClose={() => setOpenCall(null)} onToast={toast} />}
    </>
  );
}
