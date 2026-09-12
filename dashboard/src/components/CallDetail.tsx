import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useDataSource } from '../app/DataSourceProvider';
import type { Call, ReviewState, Snapshot } from '../domain/types';
import { fmtDateTime, fmtDuration, fmtMs, fmtUsd } from '../metrics/format';
import { durationSec } from '../metrics/kpis';
import { Chip, Drawer, OutcomeBadge, ReviewBadge, RiskBadge } from './ui';

/** Full call detail: metadata, transcript, timing, cost, profile changes, flags, review. */
export function CallDetail({ call, snapshot, onClose, onToast }: { call: Call; snapshot: Snapshot; onClose: () => void; onToast: (t: string) => void }) {
  const { mutate } = useDataSource();
  const caller = snapshot.callers.find((c) => c.number === call.number);
  const shop = snapshot.shops.find((s) => s.id === call.shopId);
  const device = snapshot.devices.find((d) => d.id === call.deviceId);
  const turns = snapshot.turns.filter((t) => t.callId === call.id).sort((a, b) => a.tMs - b.tMs);
  const observations = snapshot.observations.filter((o) => o.callId === call.id);
  const updates = snapshot.profileUpdates.filter((u) => u.callId === call.id);
  const followUps = snapshot.followUps.filter((f) => f.callId === call.id);
  const [note, setNote] = useState(call.adminNote ?? '');
  const [busy, setBusy] = useState(false);

  const setReview = async (state: ReviewState) => {
    setBusy(true);
    try {
      await mutate((s) => s.setCallReview(call.id, state, note || undefined));
      onToast(`Call ${call.id} marked ${state.toLowerCase()}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Drawer title={`Call ${call.id}`} onClose={onClose} testId="call-detail">
      <div className="chips">
        <OutcomeBadge outcome={call.outcome} />
        <RiskBadge risk={call.risk} />
        <ReviewBadge state={call.reviewState} />
        <Chip>{call.direction === 'INBOUND' ? 'inbound' : 'callback'}</Chip>
        {!call.hasTranscript && call.outcome === 'COMPLETED' && <Chip tone="warn">no transcript</Chip>}
        {call.summarizationError && <Chip tone="danger" title={call.summarizationError}>summarization failed</Chip>}
      </div>

      <div>
        <div className="section-title">Metadata</div>
        <dl className="kv">
          <dt>Caller</dt>
          <dd>
            <Link to={`/callers/${call.number}`}>{caller?.name ?? 'Unregistered'}</Link> <span className="mono">{call.number}</span>
          </dd>
          <dt>Shop</dt>
          <dd>{shop ? `${shop.name} · ${shop.upazila}, ${shop.district}` : call.shopId}</dd>
          <dt>Device</dt>
          <dd>{device?.label ?? call.deviceId} <span className="mono">{call.deviceId}</span></dd>
          <dt>Started</dt>
          <dd>{fmtDateTime(call.startedAt)}</dd>
          <dt>Ended</dt>
          <dd>{fmtDateTime(call.endedAt)} {call.endReason && <span className="mono">({call.endReason})</span>}</dd>
        </dl>
      </div>

      <div>
        <div className="section-title">Timing and cost</div>
        <dl className="kv">
          <dt>Duration</dt><dd>{fmtDuration(durationSec(call))}</dd>
          <dt>Audio in / out</dt><dd>{call.inputSeconds}s / {call.outputSeconds}s</dd>
          <dt>First-token latency</dt><dd>{fmtMs(call.latencyMs)}</dd>
          <dt>Estimated cost</dt><dd>{fmtUsd(call.estCostUsd, 4)}</dd>
        </dl>
      </div>

      <div>
        <div className="section-title">Transcript</div>
        {turns.length ? (
          <div className="turns" data-testid="transcript">
            {turns.map((t) => (
              <div key={t.id} className={`turn ${t.role.toLowerCase()}`} id={t.id}>
                <span className="t">{t.role === 'CALLER' ? 'Caller' : 'Assistant'} · {fmtDuration(t.tMs / 1000)}</span>
                {t.text}
              </div>
            ))}
          </div>
        ) : (
          <p style={{ color: 'var(--muted)', margin: 0 }}>No transcript captured for this call.</p>
        )}
      </div>

      <div>
        <div className="section-title">AI-extracted observations</div>
        {observations.length ? (
          <div className="chips">
            {observations.map((o) => (
              <Chip key={o.id} tone={o.category === 'risk' ? 'danger' : o.category === 'referral' ? 'purple' : o.category === 'unclassified' ? 'warn' : undefined} title={`${o.category} · confidence ${Math.round(o.confidence * 100)}%${o.evidenceTurnId ? ' · evidence: ' + o.evidenceTurnId : ''}`}>
                {o.category}: {o.label}
              </Chip>
            ))}
          </div>
        ) : (
          <p style={{ color: 'var(--muted)', margin: 0 }}>None.</p>
        )}
        <p style={{ fontSize: 12, color: 'var(--muted)', margin: '8px 0 0' }}>AI-derived, not a confirmed diagnosis.</p>
      </div>

      <div>
        <div className="section-title">Profile changes</div>
        {updates.length ? (
          <ul className="list-plain">
            {updates.map((u) => (
              <li key={u.id} style={{ fontSize: 13 }}>
                <span className="mono" style={{ color: 'var(--muted)' }}>{fmtDateTime(u.timestamp)}</span> — {u.deltaSummary}
              </li>
            ))}
          </ul>
        ) : (
          <p style={{ color: 'var(--muted)', margin: 0 }}>{call.summarizationError ? `Summarizer failed: ${call.summarizationError}` : 'No profile update recorded.'}</p>
        )}
      </div>

      {followUps.length > 0 && (
        <div>
          <div className="section-title">Follow-ups raised</div>
          <ul className="list-plain">
            {followUps.map((f) => (
              <li key={f.id} style={{ fontSize: 13 }}>
                <Link to={`/follow-ups?id=${f.id}`}>{f.id}</Link> — {f.reason} <RiskBadge risk={f.risk} />
              </li>
            ))}
          </ul>
        </div>
      )}

      <div>
        <div className="section-title">Admin review</div>
        <label className="field">
          <span>Note</span>
          <textarea className="input" rows={3} value={note} onChange={(e) => setNote(e.target.value)} placeholder="Reviewer notes (in-memory only)" />
        </label>
        <div className="form-actions" style={{ marginTop: 8 }}>
          <button className="btn" disabled={busy} onClick={() => setReview('FLAGGED')}>Flag</button>
          <button className="btn primary" disabled={busy} onClick={() => setReview('REVIEWED')}>Mark reviewed</button>
        </div>
      </div>
    </Drawer>
  );
}
