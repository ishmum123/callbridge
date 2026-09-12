import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { usePage } from '../app/PageContext';
import { CallDetail } from '../components/CallDetail';
import { DataTable, type Column } from '../components/DataTable';
import { Chip, Drawer, OutcomeBadge, Panel, RiskBadge } from '../components/ui';
import type { Call, Caller } from '../domain/types';
import { fmtDate, fmtDateTime, fmtDuration } from '../metrics/format';
import { durationSec } from '../metrics/kpis';

interface CallerRow {
  caller: Caller;
  shop: string;
  callCount: number;
  lastCall: number;
  followUp: 'PENDING' | 'OVERDUE' | 'NONE';
  risk: string[];
  hasProfile: boolean;
}

export function CallersPage() {
  const { view, snapshot, toast } = usePage();
  const params = useParams<{ number?: string }>();
  const nav = useNavigate();
  const [q, setQ] = useState('');
  const [openCall, setOpenCall] = useState<Call | null>(null);

  const rows = useMemo<CallerRow[]>(() => {
    const ref = snapshot.referenceDate;
    const needle = q.trim().toLowerCase();
    return view.callers
      .map((caller) => {
        const calls = view.calls.filter((c) => c.number === caller.number);
        const fus = view.followUps.filter((f) => f.number === caller.number && f.state === 'PENDING');
        const profile = view.profileByNumber.get(caller.number);
        return {
          caller,
          shop: view.shopById.get(caller.registeredByShop ?? '')?.name ?? '—',
          callCount: calls.length,
          lastCall: Math.max(...calls.map((c) => c.startedAt)),
          followUp: fus.some((f) => f.dueAt < ref) ? 'OVERDUE' : fus.length ? 'PENDING' : 'NONE',
          risk: profile?.riskFlags ?? [],
          hasProfile: !!profile,
        } as CallerRow;
      })
      .filter((r) => !needle || `${r.caller.number} ${r.caller.name ?? ''} ${r.caller.village ?? ''} ${r.shop}`.toLowerCase().includes(needle));
  }, [view, q, snapshot.referenceDate]);

  const cols: Column<CallerRow>[] = [
    { key: 'name', header: 'Caller', cell: (r) => <>{r.caller.name ?? <em>Unregistered</em>} <span className="mono" style={{ color: 'var(--muted)' }}>{r.caller.number}</span></>, sort: (r) => r.caller.name ?? 'zz', csv: (r) => r.caller.number },
    { key: 'shop', header: 'Shop', cell: (r) => r.shop, sort: (r) => r.shop },
    { key: 'village', header: 'Village', cell: (r) => r.caller.village ?? '—', sort: (r) => r.caller.village ?? '' },
    { key: 'calls', header: 'Calls', cell: (r) => r.callCount, sort: (r) => r.callCount, num: true },
    { key: 'last', header: 'Last call', cell: (r) => fmtDate(r.lastCall), sort: (r) => r.lastCall, csv: (r) => new Date(r.lastCall).toISOString() },
    { key: 'fu', header: 'Follow-up', cell: (r) => (r.followUp === 'NONE' ? <Chip>none</Chip> : <Chip tone={r.followUp === 'OVERDUE' ? 'danger' : 'warn'}>{r.followUp.toLowerCase()}</Chip>), sort: (r) => r.followUp, csv: (r) => r.followUp },
    { key: 'risk', header: 'Risk indicators', cell: (r) => (r.risk.length ? <div className="chips">{r.risk.slice(0, 2).map((x) => <Chip key={x} tone="danger">{x}</Chip>)}{r.risk.length > 2 && <Chip>+{r.risk.length - 2}</Chip>}</div> : <span style={{ color: 'var(--muted)' }}>—</span>), sort: (r) => r.risk.length, csv: (r) => r.risk.join('; ') },
    { key: 'profile', header: 'Profile', cell: (r) => (r.hasProfile ? <Chip tone="ok">yes</Chip> : <Chip tone="warn">missing</Chip>), sort: (r) => (r.hasProfile ? 1 : 0), csv: (r) => (r.hasProfile ? 'yes' : 'missing') },
  ];

  const open = params.number ? snapshot.callers.find((c) => c.number === params.number) ?? null : null;

  return (
    <>
      <div className="page-head">
        <div>
          <div className="eyebrow">Monitor</div>
          <h1>Callers &amp; profiles</h1>
        </div>
      </div>
      <Panel
        title={<>Callers <span style={{ color: 'var(--muted)', fontWeight: 400 }}>· {rows.length} in range</span></>}
        flush
        tools={<input className="input" style={{ width: 240 }} placeholder="Search name, number, village…" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search callers" data-testid="callers-search" />}
      >
        <DataTable rows={rows} columns={cols} rowKey={(r) => r.caller.number} pageSize={20} initialSort={{ key: 'calls', dir: 'desc' }} exportName="callers" caption="Caller list" onRowClick={(r) => nav(`/callers/${r.caller.number}${window.location.search}`)} testId="callers-table" />
      </Panel>

      {open && (
        <CallerDetail
          caller={open}
          onClose={() => nav(`/callers${window.location.search}`)}
          onOpenCall={(c) => setOpenCall(c)}
        />
      )}
      {openCall && <CallDetail call={openCall} snapshot={snapshot} onClose={() => setOpenCall(null)} onToast={toast} />}
    </>
  );
}

function CallerDetail({ caller, onClose, onOpenCall }: { caller: Caller; onClose: () => void; onOpenCall: (c: Call) => void }) {
  const { snapshot } = usePage();
  const profile = snapshot.profiles.find((p) => p.number === caller.number);
  const calls = snapshot.calls.filter((c) => c.number === caller.number).sort((a, b) => b.startedAt - a.startedAt);
  const updates = snapshot.profileUpdates.filter((u) => u.number === caller.number).sort((a, b) => b.timestamp - a.timestamp);
  const observations = snapshot.observations.filter((o) => o.number === caller.number).sort((a, b) => b.at - a.at);
  const shop = snapshot.shops.find((s) => s.id === caller.registeredByShop);
  const [tab, setTab] = useState<'profile' | 'calls' | 'history' | 'observations'>('profile');

  const list = (title: string, items: string[]) => (
    <div>
      <div className="section-title">{title}</div>
      {items.length ? <div className="chips">{items.map((x) => <Chip key={x}>{x}</Chip>)}</div> : <span style={{ color: 'var(--muted)' }}>None recorded</span>}
    </div>
  );

  return (
    <Drawer title={caller.name ?? 'Unregistered caller'} onClose={onClose} testId="caller-detail">
      <dl className="kv">
        <dt>Number</dt><dd className="mono">{caller.number}</dd>
        <dt>Village</dt><dd>{caller.village ?? '—'}</dd>
        <dt>Occupation</dt><dd>{caller.occupation ?? '—'}</dd>
        <dt>Registered by</dt><dd>{shop?.name ?? '—'} · {fmtDate(caller.createdAt)}</dd>
        {caller.languageNote && (<><dt>Language</dt><dd>{caller.languageNote}</dd></>)}
        <dt>Age / sex</dt><dd>{profile?.ageYears ?? '—'} / {profile?.sex ?? '—'}</dd>
      </dl>

      <div className="tabs" role="tablist" style={{ margin: '0 -18px' }}>
        {(['profile', 'calls', 'history', 'observations'] as const).map((t) => (
          <button key={t} role="tab" aria-selected={tab === t} onClick={() => setTab(t)}>
            {t === 'profile' ? 'Current profile' : t === 'calls' ? `Calls (${calls.length})` : t === 'history' ? `Updates (${updates.length})` : `Observations (${observations.length})`}
          </button>
        ))}
      </div>

      {tab === 'profile' && (
        profile ? (
          <>
            <div className="chips">
              {profile.followUpNeeded && <Chip tone="warn">follow-up needed</Chip>}
              {profile.lastError && <Chip tone="danger" title={profile.lastError}>last summarization failed</Chip>}
              <Chip>updated {fmtDate(profile.lastUpdated)}</Chip>
            </div>
            <p style={{ margin: 0, fontSize: 13 }}>{profile.summaryEn}</p>
            <p style={{ margin: 0, fontSize: 13, color: 'var(--muted)' }}>{profile.summaryBn}</p>
            {list('Risk flags', profile.riskFlags)}
            {list('Current symptoms', profile.currentSymptoms)}
            {list('Chronic conditions', profile.chronicConditions)}
            {list('Medications', profile.medications)}
            {list('Allergies', profile.allergies)}
            {list('Advice given', profile.adviceGiven)}
            <p style={{ fontSize: 12, color: 'var(--muted)', margin: 0 }}>AI-generated from call transcripts. Not a clinical record; requires clinician review.</p>
          </>
        ) : (
          <div className="state" style={{ padding: 20 }}>
            <h3>No profile yet</h3>
            <p style={{ margin: 0 }}>The summarizer has not produced a profile for this caller (no completed transcribed call, or the summarizer failed).</p>
          </div>
        )
      )}

      {tab === 'calls' && (
        <ul className="list-plain">
          {calls.map((c) => (
            <li key={c.id} className="feed-item" style={{ cursor: 'pointer' }} onClick={() => onOpenCall(c)}>
              <OutcomeBadge outcome={c.outcome} />
              <div>
                <div><span className="mono">{c.id}</span> · {fmtDuration(durationSec(c))} · <RiskBadge risk={c.risk} /></div>
                <div className="when">{fmtDateTime(c.startedAt)} · {snapshot.shops.find((s) => s.id === c.shopId)?.name}</div>
              </div>
            </li>
          ))}
        </ul>
      )}

      {tab === 'history' && (
        <ul className="list-plain">
          {updates.length === 0 && <li style={{ color: 'var(--muted)' }}>No profile updates recorded.</li>}
          {updates.map((u) => (
            <li key={u.id} className="feed-item">
              <div>
                <div>{u.deltaSummary}</div>
                <div className="when">{fmtDateTime(u.timestamp)} · evidence: <Link to={`/calls/${u.callId}`}>{u.callId}</Link></div>
              </div>
            </li>
          ))}
        </ul>
      )}

      {tab === 'observations' && (
        <ul className="list-plain">
          {observations.length === 0 && <li style={{ color: 'var(--muted)' }}>No observations extracted.</li>}
          {observations.map((o) => (
            <li key={o.id} className="feed-item">
              <Chip tone={o.category === 'risk' ? 'danger' : o.category === 'referral' ? 'purple' : undefined}>{o.category}</Chip>
              <div>
                <div>{o.label} <span style={{ color: 'var(--muted)', fontSize: 12 }}>· confidence {Math.round(o.confidence * 100)}%</span></div>
                <div className="when">{fmtDateTime(o.at)} · evidence: <Link to={`/calls/${o.callId}`}>{o.callId}</Link>{o.evidenceTurnId && <> / {o.evidenceTurnId}</>}</div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </Drawer>
  );
}
