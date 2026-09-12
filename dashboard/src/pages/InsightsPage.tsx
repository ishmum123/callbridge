import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { usePage } from '../app/PageContext';
import { useDataSource } from '../app/DataSourceProvider';
import { CallDetail } from '../components/CallDetail';
import { C, HBar, StackedArea } from '../components/charts';
import { downloadCsv } from '../components/DataTable';
import { IconDownload, IconFlag, IconPulse, IconUsers } from '../components/icons';
import { Chip, Drawer, KpiCard, OutcomeBadge, Panel, RiskBadge } from '../components/ui';
import { HEALTH_PACK, type AgeGroup, type Call, type Sex } from '../domain/types';
import { type Bucket, MIN_COHORT, domainMetrics, drilldown, suppressSmallCohorts } from '../metrics/domain';
import { applyFilters } from '../metrics/filters';
import { fmtDateTime, fmtInt, fmtPct } from '../metrics/format';
import { toCsv } from '../metrics/table';

const AGE_GROUPS: AgeGroup[] = ['0-4', '5-17', '18-34', '35-54', '55+', 'UNKNOWN'];

export function InsightsPage() {
  const { view, snapshot, toast } = usePage();
  const { mutate } = useDataSource();
  const [sp, setSp] = useSearchParams();
  const district = sp.get('district') ?? '';
  const village = sp.get('village') ?? '';
  const age = sp.get('age') ?? '';
  const sex = sp.get('sex') ?? '';
  const setP = (k: string, v: string) => setSp((prev) => { const p = new URLSearchParams(prev); v ? p.set(k, v) : p.delete(k); return p; }, { replace: true });

  const local = useMemo(() => {
    const f = { ...view.filters };
    if (district) f.district = district;
    if (village) f.village = village;
    if (age) f.ageGroups = [age as AgeGroup];
    if (sex) f.sexes = [sex as Sex];
    return applyFilters(snapshot, f);
  }, [snapshot, view.filters, district, village, age, sex]);

  const m = useMemo(() => domainMetrics(local, HEALTH_PACK), [local]);
  const [drill, setDrill] = useState<{ title: string; bucket: Bucket } | null>(null);
  const [openCall, setOpenCall] = useState<Call | null>(null);

  const districts = useMemo(() => [...new Set(snapshot.shops.map((s) => s.district))].sort(), [snapshot]);
  const villages = useMemo(() => [...new Set([...snapshot.shops.map((s) => s.village), ...snapshot.callers.map((c) => c.village).filter((v): v is string => !!v)])].sort(), [snapshot]);

  const exportAggregates = async () => {
    const rows: { section: string; key: string; label: string; count: number }[] = [];
    const push = (section: string, b: Bucket[]) => b.forEach((x) => rows.push({ section, key: x.key, label: x.label, count: x.count }));
    push('reach.byShop', m.reach.byShop);
    push('reach.byVillage', m.reach.byVillage);
    push('reach.byAgeGroup', m.reach.byAgeGroup);
    push('reach.bySex', m.reach.bySex);
    for (const c of HEALTH_PACK.categories) push(`category.${c.key}`, m.categories[c.key]);
    push('risk.byLevel', m.risk.byLevel);
    push('followUps.byShop', m.followUps.byShop);
    const { rows: kept, suppressed } = suppressSmallCohorts(rows);
    downloadCsv(toCsv(kept, [{ header: 'section', value: (r) => r.section }, { header: 'key', value: (r) => r.key }, { header: 'label', value: (r) => r.label }, { header: 'count', value: (r) => r.count }]), 'health-insights-aggregates.csv');
    await mutate((s) => s.recordExport('health-insights aggregates'));
    toast(`Exported ${kept.length} rows; ${suppressed} small cohorts (< ${MIN_COHORT}) suppressed.`);
  };

  const bucketPanel = (title: string, buckets: Bucket[], color = C.teal, top = 8, ai = true) => (
    <Panel title={title} aiNote={ai} tools={<span style={{ fontSize: 12, color: 'var(--muted)' }}>click a bar to drill down</span>}>
      {buckets.length ? <HBar data={buckets.slice(0, top)} color={color} onClick={(key) => { const b = buckets.find((x) => x.key === key); if (b) setDrill({ title: `${title}: ${b.label}`, bucket: b }); }} /> : <div className="state" style={{ padding: 20 }}>No data.</div>}
    </Panel>
  );

  const drillData = drill ? drilldown(local, drill.bucket) : null;

  return (
    <>
      <div className="page-head">
        <div>
          <div className="eyebrow">Domain · {HEALTH_PACK.title} pack</div>
          <h1>Health insights</h1>
        </div>
        <div className="actions">
          <button className="btn" onClick={exportAggregates} data-testid="export-aggregates"><IconDownload width={16} height={16} /> Export aggregates (CSV)</button>
        </div>
      </div>

      <div className="card" style={{ padding: 12 }}>
        <div className="filter-bar">
          <label>District<select className="select" value={district} onChange={(e) => setP('district', e.target.value)}><option value="">All</option>{districts.map((d) => <option key={d}>{d}</option>)}</select></label>
          <label>Village<select className="select" value={village} onChange={(e) => setP('village', e.target.value)}><option value="">All</option>{villages.map((d) => <option key={d}>{d}</option>)}</select></label>
          <label>Age group<select className="select" value={age} onChange={(e) => setP('age', e.target.value)} data-testid="insights-age"><option value="">All</option>{AGE_GROUPS.map((d) => <option key={d}>{d}</option>)}</select></label>
          <label>Sex<select className="select" value={sex} onChange={(e) => setP('sex', e.target.value)}><option value="">All</option><option value="F">Female</option><option value="M">Male</option><option value="UNKNOWN">Unknown</option></select></label>
          {(district || village || age || sex) && <button className="btn sm" style={{ alignSelf: 'flex-end' }} onClick={() => setSp(new URLSearchParams(), { replace: true })}>Clear</button>}
          <span style={{ marginLeft: 'auto', fontSize: 12, color: 'var(--muted)', alignSelf: 'flex-end' }}>Date range and shop come from the global filters above.</span>
        </div>
      </div>

      <div className="grid kpis">
        <KpiCard testId="ins-callers" label="Callers" value={fmtInt(m.callers.total)} sub={`${fmtInt(m.callers.new)} new · ${fmtInt(m.callers.repeat)} repeat`} icon={<IconUsers />} tone="blue" />
        <KpiCard label="Calls per caller" value={m.callers.callsPerCaller.toFixed(2)} sub={`${fmtInt(local.calls.length)} calls in range`} icon={<IconPulse />} tone="teal" />
        <KpiCard label="Risk flags" value={fmtInt(m.risk.byLevel.filter((b) => b.key === 'HIGH' || b.key === 'MEDIUM').reduce((a, b) => a + b.count, 0))} sub={`${m.risk.byLevel.find((b) => b.key === 'HIGH')?.count ?? 0} high`} icon={<IconFlag />} tone="red" />
        <KpiCard label="Follow-ups" value={fmtInt(m.followUps.pending)} unit="pending" sub={`${m.followUps.overdue} overdue · ${m.followUps.completed} completed`} icon={<IconFlag />} tone="amber" />
      </div>

      <div className="grid three">
        {bucketPanel('Reach by shop', m.reach.byShop, C.teal, 10, false)}
        {bucketPanel('Reach by village', m.reach.byVillage, C.blue, 10, false)}
        <Panel title="Reach by age group and sex">
          <HBar data={m.reach.byAgeGroup} color={C.purple} height={170} onClick={(key) => { const b = m.reach.byAgeGroup.find((x) => x.key === key); if (b) setDrill({ title: `Age ${b.label}`, bucket: b }); }} />
          <HBar data={m.reach.bySex} color={C.amber} height={110} onClick={(key) => { const b = m.reach.bySex.find((x) => x.key === key); if (b) setDrill({ title: `Sex: ${b.label}`, bucket: b }); }} />
        </Panel>
      </div>

      <div className="grid three">
        {bucketPanel('Common symptoms', m.categories.symptom)}
        {bucketPanel('Chronic conditions', m.categories.condition, C.blue)}
        {bucketPanel('Medications', m.categories.medication, C.purple)}
        {bucketPanel('Allergies', m.categories.allergy, C.amber)}
        {bucketPanel('Advice categories', m.categories.advice, C.teal)}
        {bucketPanel('Referral and escalation', m.categories.referral, C.purple)}
      </div>

      <div className="grid two">
        <Panel title="Risk flags over time" aiNote>
          <StackedArea data={m.risk.trend} series={[{ key: 'medium', name: 'Medium', color: C.amber }, { key: 'high', name: 'High', color: C.red }]} />
          <div className="chips" style={{ marginTop: 8 }}>
            {m.risk.byLevel.map((b) => (
              <Chip key={b.key} tone={b.key === 'HIGH' ? 'danger' : b.key === 'MEDIUM' ? 'warn' : b.key === 'LOW' ? 'info' : undefined} onClick={() => setDrill({ title: `Risk ${b.label}`, bucket: b })}>{b.label}: {b.count}</Chip>
            ))}
          </div>
        </Panel>
        <div style={{ display: 'grid', gap: 16 }}>
          {bucketPanel('Pending follow-ups by shop', m.followUps.byShop, C.amber, 10, false)}
          <Panel title="Data quality">
            <dl className="kv">
              <dt>Missing-profile rate</dt><dd data-testid="q-missing">{fmtPct(m.quality.missingProfileRate)}</dd>
              <dt>Transcript coverage</dt><dd>{fmtPct(m.quality.transcriptCoverage)} of {fmtInt(m.quality.completedCalls)} completed</dd>
              <dt>Unclassified observations</dt><dd>{fmtInt(m.quality.unclassifiedObservations)}</dd>
              <dt>Summarization failures</dt><dd><Link to="/calls">{fmtInt(m.quality.summarizationFailures)}</Link></dd>
            </dl>
          </Panel>
        </div>
      </div>

      {drill && drillData && (
        <Drawer title={drill.title} onClose={() => setDrill(null)} testId="drilldown">
          <div className="chips">
            <Chip tone="info">{drillData.calls.length} calls</Chip>
            <Chip tone="info">{drillData.callers.length} callers</Chip>
            {drillData.callers.length > 0 && drillData.callers.length < MIN_COHORT && <Chip tone="warn">small cohort — suppressed in exports</Chip>}
          </div>
          <div className="section-title">Contributing calls (evidence)</div>
          <ul className="list-plain">
            {drillData.calls.slice(0, 50).map((c) => (
              <li key={c.id} className="feed-item" style={{ cursor: 'pointer' }} onClick={() => setOpenCall(c)}>
                <OutcomeBadge outcome={c.outcome} />
                <div>
                  <div><span className="mono">{c.id}</span> · {local.callerByNumber.get(c.number)?.name ?? c.number} · <RiskBadge risk={c.risk} /></div>
                  <div className="when">{fmtDateTime(c.startedAt)} · {local.shopById.get(c.shopId)?.name}</div>
                </div>
              </li>
            ))}
            {drillData.calls.length > 50 && <li style={{ color: 'var(--muted)' }}>… {drillData.calls.length - 50} more</li>}
          </ul>
        </Drawer>
      )}
      {openCall && <CallDetail call={openCall} snapshot={snapshot} onClose={() => setOpenCall(null)} onToast={toast} />}
    </>
  );
}
