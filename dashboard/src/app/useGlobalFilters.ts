import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { type Filters, type RangePreset, RANGE_PRESETS, rangeFromPreset } from '../metrics/filters';

export interface GlobalFilters {
  preset: RangePreset;
  shopIds: string[];
}

/**
 * Global date-range + shop filters, stored in URL search params so every page
 * shares them and links are reproducible. Page-local filters layer on top.
 */
export function useGlobalFilters(referenceDate: number) {
  const [params, setParams] = useSearchParams();
  const presetParam = params.get('range');
  const preset: RangePreset = RANGE_PRESETS.includes(presetParam as RangePreset) ? (presetParam as RangePreset) : '30d';
  const shopIds = useMemo(() => (params.get('shops') ?? '').split(',').filter(Boolean), [params]);

  const set = useCallback(
    (next: Partial<GlobalFilters>) => {
      setParams(
        (prev) => {
          const p = new URLSearchParams(prev);
          if (next.preset) p.set('range', next.preset);
          if (next.shopIds !== undefined) {
            if (next.shopIds.length) p.set('shops', next.shopIds.join(','));
            else p.delete('shops');
          }
          return p;
        },
        { replace: true },
      );
    },
    [setParams],
  );

  const filters = useMemo<Filters>(() => ({ ...rangeFromPreset(preset, referenceDate), shopIds }), [preset, referenceDate, shopIds]);
  return { preset, shopIds, filters, set };
}
