export type SortDir = 'asc' | 'desc';

export interface SortSpec<T> {
  key: keyof T | string;
  dir: SortDir;
  /** Optional accessor when `key` is not a direct property. */
  get?: (row: T) => unknown;
}

export function sortBy<T>(rows: T[], spec: SortSpec<T> | null): T[] {
  if (!spec) return rows;
  const get = spec.get ?? ((r: T) => (r as Record<string, unknown>)[spec.key as string]);
  const mul = spec.dir === 'asc' ? 1 : -1;
  return [...rows].sort((a, b) => {
    const x = get(a);
    const y = get(b);
    if (x == null && y == null) return 0;
    if (x == null) return 1;
    if (y == null) return -1;
    if (typeof x === 'number' && typeof y === 'number') return (x - y) * mul;
    return String(x).localeCompare(String(y)) * mul;
  });
}

export interface Page<T> {
  rows: T[];
  page: number;
  pageSize: number;
  total: number;
  pages: number;
}

export function paginate<T>(rows: T[], page: number, pageSize: number): Page<T> {
  const total = rows.length;
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const p = Math.min(Math.max(1, page), pages);
  return { rows: rows.slice((p - 1) * pageSize, p * pageSize), page: p, pageSize, total, pages };
}

export interface CsvColumn<T> {
  header: string;
  value: (row: T) => unknown;
}

function csvCell(v: unknown): string {
  if (v === null || v === undefined) return '';
  const s = v instanceof Date ? v.toISOString() : String(v);
  // Guard against spreadsheet formula injection and quote when needed.
  const safe = /^[=+\-@\t\r]/.test(s) ? `'${s}` : s;
  return /[",\n\r]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
}

export function toCsv<T>(rows: T[], columns: CsvColumn<T>[]): string {
  const head = columns.map((c) => csvCell(c.header)).join(',');
  const body = rows.map((r) => columns.map((c) => csvCell(c.value(r))).join(','));
  return [head, ...body].join('\r\n') + '\r\n';
}
