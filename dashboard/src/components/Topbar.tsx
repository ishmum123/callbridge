import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useDataSource } from '../app/DataSourceProvider';
import { DEMO_MODES, type DemoMode } from '../data/MockDashboardDataSource';
import type { Shop } from '../domain/types';
import { RANGE_PRESETS, type RangePreset } from '../metrics/filters';
import { IconBell, IconMenu, IconSearch, IconSettings } from './icons';

export interface Crumb {
  label: string;
  to?: string;
}

interface Props {
  crumbs: Crumb[];
  onMenu: () => void;
  shops: Shop[];
  preset: RangePreset;
  shopIds: string[];
  onFilters: (f: { preset?: RangePreset; shopIds?: string[] }) => void;
}

const MODE_LABEL: Record<DemoMode, string> = {
  ok: 'Normal',
  loading: 'Loading (hung backend)',
  empty: 'Empty dataset',
  unauthorized: 'Unauthorized (401)',
  error: 'Server error (503)',
};

export function Topbar({ crumbs, onMenu, shops, preset, shopIds, onFilters }: Props) {
  const { demoMode, setDemoMode } = useDataSource();
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const nav = useNavigate();
  const popRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (popRef.current && !popRef.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', onDoc);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDoc);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <header className="topbar">
      <button className="icon-btn" aria-label="Toggle navigation" onClick={onMenu}>
        <IconMenu width={20} height={20} />
      </button>
      <nav className="crumbs" aria-label="Breadcrumb">
        {crumbs.map((c, i) => (
          <span key={i} style={{ display: 'contents' }}>
            {i > 0 && <span aria-hidden>›</span>}
            {c.to && i < crumbs.length - 1 ? <Link to={c.to}>{c.label}</Link> : <strong>{c.label}</strong>}
          </span>
        ))}
      </nav>

      <form
        className="topbar-search"
        role="search"
        onSubmit={(e) => {
          e.preventDefault();
          nav(`/calls?q=${encodeURIComponent(q)}`);
        }}
      >
        <IconSearch />
        <label className="sr-only" htmlFor="global-search">Search calls, callers, shops</label>
        <input id="global-search" placeholder="Search calls, callers, shops…" value={q} onChange={(e) => setQ(e.target.value)} />
      </form>

      <div className="topbar-right">
        <label className="sr-only" htmlFor="range-select">Date range</label>
        <select id="range-select" className="select" value={preset} onChange={(e) => onFilters({ preset: e.target.value as RangePreset })} aria-label="Date range">
          {RANGE_PRESETS.map((p) => (
            <option key={p} value={p}>Last {p.replace('d', ' days')}</option>
          ))}
        </select>
        <label className="sr-only" htmlFor="shop-select">Shop filter</label>
        <select id="shop-select" className="select" value={shopIds[0] ?? ''} onChange={(e) => onFilters({ shopIds: e.target.value ? [e.target.value] : [] })} aria-label="Shop filter">
          <option value="">All shops</option>
          {shops.map((s) => (
            <option key={s.id} value={s.id}>{s.name}</option>
          ))}
        </select>

        <span className="demo-pill" role="status" aria-live="polite" data-testid="demo-pill">
          <span className="dot" aria-hidden /> Demo data
        </span>

        <div style={{ position: 'relative' }} ref={popRef}>
          <button className="icon-btn" aria-label="Demo controls" aria-expanded={open} aria-haspopup="dialog" onClick={() => setOpen((v) => !v)} data-testid="demo-controls">
            <IconSettings width={18} height={18} />
          </button>
          {open && (
            <div className="popover" role="dialog" aria-label="Demo controls">
              <h4>Simulated backend state</h4>
              {DEMO_MODES.map((m) => (
                <label key={m} style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 13 }}>
                  <input type="radio" name="demo-mode" value={m} checked={demoMode === m} onChange={() => setDemoMode(m)} />
                  {MODE_LABEL[m]}
                </label>
              ))}
              <small style={{ color: 'var(--muted)' }}>Deterministic fixture, seed 20260912, reference date 2026-09-12. In-memory edits reset on reload.</small>
            </div>
          )}
        </div>
        <button className="icon-btn" aria-label="Notifications">
          <IconBell width={18} height={18} />
        </button>
        <div className="avatar" aria-label="Admin">A</div>
      </div>
    </header>
  );
}
