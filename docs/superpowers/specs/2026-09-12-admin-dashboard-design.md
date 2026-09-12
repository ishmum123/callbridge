# Admin dashboard prototype — design

Resolves GitHub issue #10. Frontend-only, mocked-data prototype of the central CallBridge admin dashboard. Visual language follows the Gentelella v4 admin template (dark sidebar, light content, KPI cards, chart panels, dense tables), with CallBridge content.

## Goals

- Give admins one browser UI to monitor shops, devices, callers, calls, health-domain outcomes, costs, and follow-ups.
- Keep a typed data-source boundary so a real HTTP API can replace the mock without touching pages.
- Every displayed number is derived from raw fixture records at runtime, never hardcoded.
- Deterministic dataset (fixed seed, fixed reference date) so tests and screenshots repeat.

## Non-goals

Backend, database, sync, real auth, shopkeeper web access, live listening, billing, multiple organizations, automated diagnosis. Same list as the issue.

## Location and stack

- `dashboard/` at repo root. Independent from the Gradle build.
- Vite 6, React 19, TypeScript strict, React Router 7, Recharts, Vitest, Playwright, plain CSS with custom-property tokens.
- Node 20+. Commands: `npm run dev`, `npm test`, `npm run test:e2e`, `npm run build`.

## Architecture

```
src/
  domain/      types only. Records + observation contract.
  data/        DashboardDataSource interface, MockDashboardDataSource, seeded generator, DataSourceProvider.
  metrics/     pure functions: filters, KPIs, trends, domain aggregates, cohort suppression, CSV.
  app/         router, shell layout, global filter state (URL search params), theme tokens.
  components/  Sidebar, Topbar, KpiCard, ChartPanel, DataTable, StatusBadge, DemoBanner, StateView.
  pages/       Overview, Calls, CallDetail, Callers, CallerDetail, Insights, FollowUps, Shops, Admin.
```

Dependency direction: pages → components + metrics + data. metrics depends only on domain. data depends only on domain.

### Domain records (`src/domain`)

Mirrors of Room entities, keyed the same way so a future sync maps 1:1:

- `Caller { number, name?, village?, occupation?, languageNote?, registeredByShop?, createdAt }`
- `Call { id, number, shopId, deviceId, direction: 'INBOUND'|'OUTBOUND_CALLBACK', startedAt, endedAt?, endReason?, outcome: 'COMPLETED'|'FAILED'|'NO_ANSWER'|'HUNG_UP', inputSeconds, outputSeconds, estCostUsd, latencyMs, hasTranscript, summarizationError?, reviewState: 'UNREVIEWED'|'REVIEWED'|'FLAGGED', adminNote? }`
- `Turn { id, callId, role: 'CALLER'|'ASSISTANT', text, tMs }`
- `PatientProfile` — same fields as `PatientProfileEntity`.
- `ProfileUpdate { id, number, callId, timestamp, deltaSummary }`

Dashboard-only records:

- `Organization { id, name }` (exactly one).
- `Shop { id, orgId, name, district, upazila, village, shopkeeperId, createdAt }`
- `Shopkeeper { id, name, phone, shopId }`
- `Device { id, shopId, label, appVersion, simId, lastSeenAt, lastCallAt?, syncState: 'SYNCED'|'PENDING'|'ERROR', status: 'ONLINE'|'OFFLINE', recentErrors: string[] }`
- `FollowUp { id, number, callId, shopId, reason, risk: 'LOW'|'MEDIUM'|'HIGH', dueAt, state: 'PENDING'|'REVIEWED'|'COMPLETED', notes: {at, text}[] }`
- `AuditEntry { id, at, actor, action, target }`

### Observation contract

```ts
interface Observation {
  id: string; callId: string; number: string; at: number;
  pack: 'health' | 'agriculture';     // agriculture reserved, not seeded in v1
  category: string;                   // health: symptom | condition | medication | allergy | advice | risk | referral
  code: string;                       // normalized label, e.g. 'fever'
  label: string;                      // display text
  confidence: number;                 // 0..1, AI-derived
  evidenceTurnId?: string;            // link back to transcript turn
}
```

Domain pages group by `pack` then `category`. A metric pack is a config object `{ pack, categories: [{ key, title }], cohortDimensions }`. v1 ships `healthPack`. An agriculture pack adds categories (crop, pest, disease, price_inquiry, location, advice) without UI changes.

### Data source (`src/data`)

```ts
interface DashboardDataSource {
  getSnapshot(): Promise<Snapshot>;             // all records; pages filter client-side
  addFollowUpNote(id, text): Promise<FollowUp>;
  setFollowUpState(id, state): Promise<FollowUp>;
  setCallReview(callId, state, note?): Promise<Call>;
  upsertShop(shop): Promise<Shop>;
  upsertShopkeeper(sk): Promise<Shopkeeper>;
  assignDevice(deviceId, shopId): Promise<Device>;
  getAudit(): Promise<AuditEntry[]>;
}
```

`Snapshot` bundles every record array plus `generatedAt` and `isDemo: true`. Filtering happens in `metrics/` so the same functions can later run server-side. This is the trade-off chosen over per-page query endpoints: simpler contract now, and an HTTP adapter can still implement `getSnapshot` with one call or several.

`MockDashboardDataSource(seed, referenceDate, { latencyMs, mode })` where `mode` is `'ok' | 'loading' | 'empty' | 'unauthorized' | 'error'`. Mode is switchable from a Demo controls popover in the topbar and via `?demo=error` query param for tests. Mutations update in-memory arrays and append an `AuditEntry`; reload resets.

Generator: mulberry32 PRNG seeded with `20260912`. Reference date 2026-09-12T00:00Z. Produces 1 org, 10 shops, 12 devices (2 offline, 1 sync error), 300 callers, ~1,000 calls over 90 days with a weekday curve, ~15% failed, ~10% callbacks, transcripts on ~85% of completed calls, profiles for ~80% of callers, ~6% summarization failures, follow-ups for ~12% of callers, health observations drawn from fixed vocabularies with Bengali-region village and name lists.

### Metrics (`src/metrics`)

All pure, all unit tested:

- `applyFilters(snapshot, filters)` → filtered view. Filters: date range, shopIds, district, upazila, village, ageGroup, sex, direction, outcome, risk, category, search text.
- `overviewKpis(view)` → calls, uniqueCallers, avgDurationSec, estCost, costPerCall, failedRate, pendingFollowUps, devicesOnline/offline.
- `dailySeries(view)` → `{ date, calls, cost, failed }[]` for the range.
- `shopComparison(view)`.
- `healthMetrics(view, pack)` → callers total/new/repeat, callsPerCaller, reach by shop/village/ageGroup/sex, top codes per category, risk trend, follow-up buckets, referral categories, data-quality rates.
- `drilldown(view, dimension, value)` → contributing calls/callers.
- `suppressSmallCohorts(rows, min = 5)` for exports.
- `paginate`, `sortBy`, `toCsv`.

### Global filter state

Date range preset (7/30/90 days or custom) and shop selection live in URL search params via a `useGlobalFilters` hook. Every page reads them; page-local filters layer on top.

### Shell and visual design

- Sidebar 260px, navy `#1f2937`-family background, section labels (Monitor, Domain, Operate, Admin), active item with left accent bar. Collapses to icon rail under 1024px, off-canvas under 768px.
- Topbar: hamburger, breadcrumb, global search, date-range and shop selectors, Demo data pill (always visible, amber), demo-mode controls, avatar.
- KPI card: icon tile, label, big number, delta chip, sparkline.
- Panels with title row and right-aligned controls. Tables dense with sticky header.
- Accent palette: teal primary, red danger, amber warn, purple info. Contrast ≥ 4.5:1 checked on text tokens.
- Every AI-derived section carries a caption "AI-extracted from call transcripts. Not a clinical diagnosis."

### States

`StateView` renders loading skeleton, empty, unauthorized (403 illustration + "Sign in as admin" no-op), and error (message + retry). Pages render it whenever the snapshot query is not in `ok`.

### Testing

- Vitest: generator determinism (same seed ⇒ same hash), every metrics function, filters, pagination, sorting, CSV escaping, cohort suppression, mock source modes and mutations.
- Playwright: sidebar navigation to all sections, global filter changes update Overview KPI and Calls count, Calls search + open detail + transcript visible, follow-up note + complete, demo mode error/empty/unauthorized render, keyboard tab order on Overview.
- Accessibility: landmarks, labelled controls, focus rings, `prefers-reduced-motion`, axe check in one Playwright test.

## Open risks

- Recharts bundle size is fine for a prototype; swap later if needed.
- Client-side filtering of 1,000 calls is trivial; not a concern under 50k.
