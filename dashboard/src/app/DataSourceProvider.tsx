import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import type { Snapshot } from '../domain/types';
import { type DashboardDataSource, ServerError, UnauthorizedError } from '../data/DashboardDataSource';
import { DEMO_MODES, MockDashboardDataSource, type DemoMode } from '../data/MockDashboardDataSource';

export type QueryStatus = 'loading' | 'ok' | 'empty' | 'unauthorized' | 'error';

export interface SnapshotQuery {
  status: QueryStatus;
  snapshot: Snapshot | null;
  error: string | null;
}

interface Ctx {
  source: DashboardDataSource;
  query: SnapshotQuery;
  refresh(): Promise<void>;
  /** Run a mutation then re-read the snapshot. */
  mutate<T>(fn: (s: DashboardDataSource) => Promise<T>): Promise<T>;
  demoMode: DemoMode;
  setDemoMode(mode: DemoMode): void;
}

const DataSourceContext = createContext<Ctx | null>(null);

function initialMode(): DemoMode {
  const p = new URLSearchParams(window.location.search).get('demo');
  return DEMO_MODES.includes(p as DemoMode) ? (p as DemoMode) : 'ok';
}

export function DataSourceProvider({ children, source: injected }: { children: ReactNode; source?: MockDashboardDataSource }) {
  const sourceRef = useRef<MockDashboardDataSource>(injected ?? new MockDashboardDataSource({ mode: initialMode() }));
  const source = sourceRef.current;
  const [demoMode, setDemoModeState] = useState<DemoMode>(source.mode);
  const [query, setQuery] = useState<SnapshotQuery>({ status: 'loading', snapshot: null, error: null });
  const gen = useRef(0);

  const refresh = useCallback(async () => {
    const my = ++gen.current;
    setQuery((q) => ({ ...q, status: 'loading', error: null }));
    try {
      const snap = await source.getSnapshot();
      if (my !== gen.current) return;
      setQuery({ status: snap.calls.length === 0 ? 'empty' : 'ok', snapshot: snap, error: null });
    } catch (e) {
      if (my !== gen.current) return;
      if (e instanceof UnauthorizedError) setQuery({ status: 'unauthorized', snapshot: null, error: e.message });
      else if (e instanceof ServerError) setQuery({ status: 'error', snapshot: null, error: `${e.status}: ${e.message}` });
      else setQuery({ status: 'error', snapshot: null, error: (e as Error).message });
    }
  }, [source]);

  const setDemoMode = useCallback(
    (mode: DemoMode) => {
      source.mode = mode;
      setDemoModeState(mode);
      const url = new URL(window.location.href);
      if (mode === 'ok') url.searchParams.delete('demo');
      else url.searchParams.set('demo', mode);
      window.history.replaceState(null, '', url);
      void refresh();
    },
    [source, refresh],
  );

  const mutate = useCallback(
    async <T,>(fn: (s: DashboardDataSource) => Promise<T>) => {
      const out = await fn(source);
      const snap = await source.getSnapshot();
      setQuery({ status: snap.calls.length === 0 ? 'empty' : 'ok', snapshot: snap, error: null });
      return out;
    },
    [source],
  );

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const value = useMemo<Ctx>(() => ({ source, query, refresh, mutate, demoMode, setDemoMode }), [source, query, refresh, mutate, demoMode, setDemoMode]);
  return <DataSourceContext.Provider value={value}>{children}</DataSourceContext.Provider>;
}

export function useDataSource(): Ctx {
  const ctx = useContext(DataSourceContext);
  if (!ctx) throw new Error('useDataSource must be used inside DataSourceProvider');
  return ctx;
}
