import { useOutletContext } from 'react-router-dom';
import type { Snapshot } from '../domain/types';
import type { FilteredView, RangePreset } from '../metrics/filters';

export interface PageCtx {
  snapshot: Snapshot;
  /** Snapshot narrowed by the global date-range + shop filters. */
  view: FilteredView;
  preset: RangePreset;
  shopIds: string[];
  setGlobal: (f: { preset?: RangePreset; shopIds?: string[] }) => void;
  toast: (text: string) => void;
}

export function usePage(): PageCtx {
  return useOutletContext<PageCtx>();
}
