import { useEffect, useMemo, useState } from 'react';
import { BrowserRouter, Outlet, Route, Routes, useLocation } from 'react-router-dom';
import { Sidebar } from '../components/Sidebar';
import { Topbar, type Crumb } from '../components/Topbar';
import { StateView, Toast } from '../components/ui';
import { applyFilters } from '../metrics/filters';
import { AdminPage } from '../pages/AdminPage';
import { CallersPage } from '../pages/CallersPage';
import { CallsPage } from '../pages/CallsPage';
import { FollowUpsPage } from '../pages/FollowUpsPage';
import { InsightsPage } from '../pages/InsightsPage';
import { OverviewPage } from '../pages/OverviewPage';
import { ShopsPage } from '../pages/ShopsPage';
import { DataSourceProvider, useDataSource } from './DataSourceProvider';
import type { PageCtx } from './PageContext';
import { useGlobalFilters } from './useGlobalFilters';
import './theme.css';

const TITLES: Record<string, string> = { '': 'Overview', calls: 'Calls', callers: 'Callers & profiles', insights: 'Health insights', 'follow-ups': 'Follow-ups', shops: 'Shops & devices', admin: 'Administration' };

function Shell() {
  const { query, refresh, setDemoMode } = useDataSource();
  const loc = useLocation();
  const [navOpen, setNavOpen] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const referenceDate = query.snapshot?.referenceDate ?? Date.UTC(2026, 8, 12);
  const { preset, shopIds, filters, set } = useGlobalFilters(referenceDate);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 2500);
    return () => clearTimeout(t);
  }, [toast]);

  const view = useMemo(() => (query.snapshot ? applyFilters(query.snapshot, filters) : null), [query.snapshot, filters]);
  const seg = loc.pathname.split('/')[1] ?? '';
  const crumbs: Crumb[] = [{ label: 'Home', to: '/' }, { label: TITLES[seg] ?? 'Dashboard' }];
  const pending = view?.followUps.filter((f) => f.state === 'PENDING').length ?? 0;

  const ctx: PageCtx | null = query.snapshot && view ? { snapshot: query.snapshot, view, preset, shopIds, setGlobal: set, toast: setToast } : null;

  return (
    <div className="shell">
      <Sidebar open={navOpen} onNavigate={() => setNavOpen(false)} pendingFollowUps={pending} />
      <div className="main">
        <Topbar crumbs={crumbs} onMenu={() => setNavOpen((v) => !v)} shops={query.snapshot?.shops ?? []} preset={preset} shopIds={shopIds} onFilters={set} />
        {query.status === 'ok' && ctx ? (
          <main className="content" id="main">
            <Outlet context={ctx} />
          </main>
        ) : (
          <main id="main">
            <StateView status={query.status === 'ok' ? 'loading' : query.status} error={query.error} onRetry={() => (query.status === 'unauthorized' ? setDemoMode('ok') : refresh())} />
          </main>
        )}
      </div>
      <Toast text={toast} />
    </div>
  );
}

export function App() {
  return (
    <DataSourceProvider>
      <BrowserRouter>
        <Routes>
          <Route element={<Shell />}>
            <Route index element={<OverviewPage />} />
            <Route path="calls" element={<CallsPage />} />
            <Route path="calls/:id" element={<CallsPage />} />
            <Route path="callers" element={<CallersPage />} />
            <Route path="callers/:number" element={<CallersPage />} />
            <Route path="insights" element={<InsightsPage />} />
            <Route path="follow-ups" element={<FollowUpsPage />} />
            <Route path="shops" element={<ShopsPage />} />
            <Route path="admin" element={<AdminPage />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </DataSourceProvider>
  );
}
