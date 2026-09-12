/**
 * Domain records for the admin dashboard.
 *
 * The first block mirrors the Android Room entities in
 * app/src/main/java/bd/callbridge/store/Entities.kt so a future sync can map
 * 1:1. The second block is dashboard-only (org / shop / device hierarchy,
 * follow-ups, audit). Nothing here talks to the phone database directly.
 */

export type CallDirection = 'INBOUND' | 'OUTBOUND_CALLBACK';
export type CallOutcome = 'COMPLETED' | 'FAILED' | 'NO_ANSWER' | 'HUNG_UP';
export type ReviewState = 'UNREVIEWED' | 'REVIEWED' | 'FLAGGED';
export type TurnRole = 'CALLER' | 'ASSISTANT';
export type Sex = 'F' | 'M' | 'UNKNOWN';
export type RiskLevel = 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH';

/** Mirrors CallerEntity. Keyed by phone number. */
export interface Caller {
  number: string;
  name: string | null;
  village: string | null;
  occupation: string | null;
  languageNote: string | null;
  registeredByShop: string | null;
  createdAt: number;
}

/** Mirrors CallEntity plus dashboard-side attribution and review fields. */
export interface Call {
  id: string;
  number: string;
  shopId: string;
  deviceId: string;
  direction: CallDirection;
  startedAt: number;
  endedAt: number | null;
  endReason: string | null;
  outcome: CallOutcome;
  inputSeconds: number;
  outputSeconds: number;
  estCostUsd: number;
  /** First-token latency of the Gemini Live session, ms. */
  latencyMs: number;
  hasTranscript: boolean;
  /** Set when the post-call profile summarizer failed. */
  summarizationError: string | null;
  risk: RiskLevel;
  reviewState: ReviewState;
  adminNote: string | null;
}

/** Mirrors TurnEntity. */
export interface Turn {
  id: string;
  callId: string;
  role: TurnRole;
  text: string;
  tMs: number;
}

/** Mirrors PatientProfileEntity. */
export interface PatientProfile {
  number: string;
  displayName: string | null;
  ageYears: number | null;
  sex: Sex;
  village: string | null;
  chronicConditions: string[];
  currentSymptoms: string[];
  medications: string[];
  allergies: string[];
  riskFlags: string[];
  adviceGiven: string[];
  followUpNeeded: boolean;
  followUpNote: string | null;
  summaryBn: string;
  summaryEn: string;
  lastUpdated: number;
  callCount: number;
  lastError: string | null;
}

/** Mirrors ProfileUpdateEntity. */
export interface ProfileUpdate {
  id: string;
  number: string;
  callId: string;
  timestamp: number;
  deltaSummary: string;
}

// ---------------------------------------------------------------------------
// Dashboard-only records
// ---------------------------------------------------------------------------

export interface Organization {
  id: string;
  name: string;
}

export interface Shop {
  id: string;
  orgId: string;
  name: string;
  district: string;
  upazila: string;
  village: string;
  shopkeeperId: string | null;
  createdAt: number;
}

export interface Shopkeeper {
  id: string;
  name: string;
  phone: string;
  shopId: string | null;
  active: boolean;
}

export type DeviceStatus = 'ONLINE' | 'OFFLINE';
export type SyncState = 'SYNCED' | 'PENDING' | 'ERROR';

export interface Device {
  id: string;
  shopId: string | null;
  label: string;
  appVersion: string;
  simId: string;
  lastSeenAt: number;
  lastCallAt: number | null;
  syncState: SyncState;
  status: DeviceStatus;
  recentErrors: string[];
}

export type FollowUpState = 'PENDING' | 'REVIEWED' | 'COMPLETED';

export interface FollowUpNote {
  at: number;
  actor: string;
  text: string;
}

export interface FollowUp {
  id: string;
  number: string;
  callId: string;
  shopId: string;
  reason: string;
  risk: RiskLevel;
  createdAt: number;
  dueAt: number;
  state: FollowUpState;
  notes: FollowUpNote[];
}

export interface AuditEntry {
  id: string;
  at: number;
  actor: string;
  action: string;
  target: string;
}

// ---------------------------------------------------------------------------
// Domain observation contract
// ---------------------------------------------------------------------------

/**
 * A single AI-extracted observation from one call. Metric packs group these by
 * `pack` then `category`. v1 seeds only the health pack; an agriculture pack
 * can add categories (crop, pest, disease, price_inquiry, location, advice)
 * without changing the dashboard UI.
 */
export type ObservationPack = 'health' | 'agriculture';

export interface Observation {
  id: string;
  callId: string;
  number: string;
  at: number;
  pack: ObservationPack;
  category: string;
  /** Normalised label used for grouping, e.g. 'fever'. */
  code: string;
  /** Display text. */
  label: string;
  /** 0..1, model self-reported. Always presented as AI-derived. */
  confidence: number;
  /** Link back to the transcript turn that produced it, when known. */
  evidenceTurnId: string | null;
}

export interface MetricCategory {
  key: string;
  title: string;
}

export interface MetricPack {
  pack: ObservationPack;
  title: string;
  categories: MetricCategory[];
}

export const HEALTH_PACK: MetricPack = {
  pack: 'health',
  title: 'Health',
  categories: [
    { key: 'symptom', title: 'Common symptoms' },
    { key: 'condition', title: 'Chronic conditions' },
    { key: 'medication', title: 'Medications' },
    { key: 'allergy', title: 'Allergies' },
    { key: 'advice', title: 'Advice categories' },
    { key: 'risk', title: 'Risk flags' },
    { key: 'referral', title: 'Referral and escalation' },
  ],
};

// ---------------------------------------------------------------------------
// Snapshot: what a data source returns
// ---------------------------------------------------------------------------

export interface Snapshot {
  generatedAt: number;
  referenceDate: number;
  isDemo: boolean;
  organization: Organization;
  shops: Shop[];
  shopkeepers: Shopkeeper[];
  devices: Device[];
  callers: Caller[];
  calls: Call[];
  turns: Turn[];
  profiles: PatientProfile[];
  profileUpdates: ProfileUpdate[];
  observations: Observation[];
  followUps: FollowUp[];
  audit: AuditEntry[];
}

export type AgeGroup = '0-4' | '5-17' | '18-34' | '35-54' | '55+' | 'UNKNOWN';

export function ageGroupOf(age: number | null): AgeGroup {
  if (age === null || Number.isNaN(age)) return 'UNKNOWN';
  if (age < 5) return '0-4';
  if (age < 18) return '5-17';
  if (age < 35) return '18-34';
  if (age < 55) return '35-54';
  return '55+';
}
