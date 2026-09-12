import { Area, AreaChart, Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';

export const C = { teal: '#1abb9c', blue: '#3b82f6', amber: '#f0a92e', red: '#e04b4b', purple: '#8b5cf6', grey: '#cbd5e1' };

const axis = { stroke: '#94a3b8', fontSize: 11 };
const shortDate = (d: string) => d.slice(5);

export function TrendChart({ data, series, height = 260 }: { data: object[]; series: { key: string; name: string; color: string; axis?: 'left' | 'right' }[]; height?: number }) {
  const hasRight = series.some((s) => s.axis === 'right');
  return (
    <div style={{ width: '100%', height }} role="img" aria-label={`Trend of ${series.map((s) => s.name).join(', ')}`}>
      <ResponsiveContainer>
        <LineChart data={data} margin={{ top: 8, right: hasRight ? 8 : 16, bottom: 0, left: -12 }}>
          <CartesianGrid stroke="#eef2f7" vertical={false} />
          <XAxis dataKey="date" tickFormatter={shortDate} tick={axis} tickLine={false} axisLine={false} minTickGap={24} />
          <YAxis yAxisId="left" tick={axis} tickLine={false} axisLine={false} allowDecimals={false} />
          {hasRight && <YAxis yAxisId="right" orientation="right" tick={axis} tickLine={false} axisLine={false} tickFormatter={(v: number) => `$${v.toFixed(1)}`} />}
          <Tooltip contentStyle={{ fontSize: 12, borderRadius: 8, border: '1px solid #e3e8ef' }} />
          <Legend iconType="plainline" wrapperStyle={{ fontSize: 12 }} />
          {series.map((s) => (
            <Line key={s.key} yAxisId={s.axis ?? 'left'} type="monotone" dataKey={s.key} name={s.name} stroke={s.color} strokeWidth={2} dot={false} isAnimationActive={false} strokeDasharray={s.axis === 'right' ? '4 3' : undefined} />
          ))}
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}

export function StackedArea({ data, series, height = 220 }: { data: object[]; series: { key: string; name: string; color: string }[]; height?: number }) {
  return (
    <div style={{ width: '100%', height }} role="img" aria-label={`Stacked trend of ${series.map((s) => s.name).join(', ')}`}>
      <ResponsiveContainer>
        <AreaChart data={data} margin={{ top: 8, right: 16, bottom: 0, left: -12 }}>
          <CartesianGrid stroke="#eef2f7" vertical={false} />
          <XAxis dataKey="date" tickFormatter={shortDate} tick={axis} tickLine={false} axisLine={false} minTickGap={24} />
          <YAxis tick={axis} tickLine={false} axisLine={false} allowDecimals={false} />
          <Tooltip contentStyle={{ fontSize: 12, borderRadius: 8, border: '1px solid #e3e8ef' }} />
          <Legend wrapperStyle={{ fontSize: 12 }} />
          {series.map((s) => (
            <Area key={s.key} type="monotone" stackId="1" dataKey={s.key} name={s.name} stroke={s.color} fill={s.color} fillOpacity={0.25} isAnimationActive={false} />
          ))}
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}

export function HBar({ data, color = C.teal, height, onClick, label = 'count' }: { data: { label: string; count: number; key: string }[]; color?: string; height?: number; onClick?: (key: string) => void; label?: string }) {
  const h = height ?? Math.max(120, data.length * 28 + 20);
  return (
    <div style={{ width: '100%', height: h }} role="img" aria-label={`Bar chart of ${label} by category`}>
      <ResponsiveContainer>
        <BarChart data={data} layout="vertical" margin={{ top: 4, right: 24, bottom: 0, left: 8 }} barCategoryGap={6}>
          <XAxis type="number" tick={axis} tickLine={false} axisLine={false} allowDecimals={false} />
          <YAxis type="category" dataKey="label" width={150} tick={{ ...axis, fill: '#1f2937' }} tickLine={false} axisLine={false} />
          <Tooltip contentStyle={{ fontSize: 12, borderRadius: 8, border: '1px solid #e3e8ef' }} cursor={{ fill: '#f3f5f9' }} />
          <Bar dataKey="count" name={label} fill={color} radius={[0, 4, 4, 0]} isAnimationActive={false} onClick={onClick ? ((d: unknown) => { const k = (d as { key?: unknown }).key; if (typeof k === 'string') onClick(k); }) : undefined} cursor={onClick ? 'pointer' : undefined} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
