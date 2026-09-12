import { useMemo, useState, type ReactNode } from 'react';
import { type CsvColumn, paginate, sortBy, type SortDir, toCsv } from '../metrics/table';
import { IconDownload } from './icons';
import { EmptyRows } from './ui';

export interface Column<T> {
  key: string;
  header: string;
  /** Cell renderer. */
  cell: (row: T) => ReactNode;
  /** Sort accessor; omit to make the column unsortable. */
  sort?: (row: T) => unknown;
  /** CSV value; defaults to `sort` then to text of cell. */
  csv?: (row: T) => unknown;
  num?: boolean;
  width?: string | number;
}

interface Props<T> {
  rows: T[];
  columns: Column<T>[];
  rowKey: (row: T) => string;
  onRowClick?: (row: T) => void;
  pageSize?: number;
  initialSort?: { key: string; dir: SortDir };
  /** Name for CSV export; when set, an Export button appears. */
  exportName?: string;
  onExport?: (csv: string, filename: string) => void;
  emptyText?: string;
  caption?: string;
  testId?: string;
}

export function downloadCsv(csv: string, filename: string) {
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function DataTable<T>({ rows, columns, rowKey, onRowClick, pageSize = 15, initialSort, exportName, onExport, emptyText, caption, testId }: Props<T>) {
  const [sort, setSort] = useState<{ key: string; dir: SortDir } | null>(initialSort ?? null);
  const [page, setPage] = useState(1);

  const sorted = useMemo(() => {
    const col = sort && columns.find((c) => c.key === sort.key);
    return col?.sort ? sortBy(rows, { key: sort!.key, dir: sort!.dir, get: col.sort }) : rows;
  }, [rows, sort, columns]);
  const pg = paginate(sorted, page, pageSize);

  const toggle = (key: string) => {
    setSort((s) => (s?.key === key ? { key, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { key, dir: 'asc' }));
    setPage(1);
  };

  const exportCsv = () => {
    const cols: CsvColumn<T>[] = columns.map((c) => ({ header: c.header, value: c.csv ?? c.sort ?? ((r) => textOf(c.cell(r))) }));
    const csv = toCsv(sorted, cols);
    const filename = `${exportName ?? 'export'}-${new Date().toISOString().slice(0, 10)}.csv`;
    onExport ? onExport(csv, filename) : downloadCsv(csv, filename);
  };

  return (
    <div data-testid={testId}>
      <div className="table-wrap">
        <table className="data">
          {caption && <caption className="sr-only">{caption}</caption>}
          <thead>
            <tr>
              {columns.map((c) => (
                <th key={c.key} className={c.num ? 'num' : undefined} style={{ width: c.width }} aria-sort={sort?.key === c.key ? (sort.dir === 'asc' ? 'ascending' : 'descending') : undefined}>
                  {c.sort ? (
                    <button type="button" onClick={() => toggle(c.key)}>
                      {c.header}
                      <span aria-hidden>{sort?.key === c.key ? (sort.dir === 'asc' ? '↑' : '↓') : '↕'}</span>
                    </button>
                  ) : (
                    c.header
                  )}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {pg.rows.map((r) => (
              <tr
                key={rowKey(r)}
                className={onRowClick ? 'clickable' : undefined}
                onClick={onRowClick ? () => onRowClick(r) : undefined}
                tabIndex={onRowClick ? 0 : undefined}
                onKeyDown={onRowClick ? (e) => (e.key === 'Enter' || e.key === ' ') && (e.preventDefault(), onRowClick(r)) : undefined}
              >
                {columns.map((c) => (
                  <td key={c.key} className={c.num ? 'num' : undefined}>{c.cell(r)}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
        {pg.total === 0 && <EmptyRows>{emptyText}</EmptyRows>}
      </div>
      <div className="table-foot">
        <span>
          {pg.total === 0 ? '0 rows' : `${(pg.page - 1) * pageSize + 1}–${Math.min(pg.page * pageSize, pg.total)} of ${pg.total}`}
        </span>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          {exportName && (
            <button className="btn sm" onClick={exportCsv} disabled={pg.total === 0}>
              <IconDownload width={14} height={14} /> Export CSV
            </button>
          )}
          {pg.pages > 1 && (
            <nav className="pager" aria-label="Pagination">
              <button className="btn sm" onClick={() => setPage(1)} disabled={pg.page === 1} aria-label="First page">«</button>
              <button className="btn sm" onClick={() => setPage((p) => p - 1)} disabled={pg.page === 1} aria-label="Previous page">‹</button>
              <span style={{ padding: '0 6px' }}>Page {pg.page} / {pg.pages}</span>
              <button className="btn sm" onClick={() => setPage((p) => p + 1)} disabled={pg.page === pg.pages} aria-label="Next page">›</button>
              <button className="btn sm" onClick={() => setPage(pg.pages)} disabled={pg.page === pg.pages} aria-label="Last page">»</button>
            </nav>
          )}
        </div>
      </div>
    </div>
  );
}

function textOf(node: ReactNode): string {
  if (node == null || typeof node === 'boolean') return '';
  if (typeof node === 'string' || typeof node === 'number') return String(node);
  if (Array.isArray(node)) return node.map(textOf).join(' ');
  if (typeof node === 'object' && 'props' in node) return textOf((node as { props: { children?: ReactNode } }).props.children);
  return '';
}
