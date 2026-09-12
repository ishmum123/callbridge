import {
  type AuditEntry,
  type Call,
  type CallOutcome,
  type Caller,
  type Device,
  type FollowUp,
  type FollowUpState,
  type Observation,
  type PatientProfile,
  type ProfileUpdate,
  type RiskLevel,
  type Sex,
  type Shop,
  type Shopkeeper,
  type Snapshot,
  type Turn,
} from '../domain/types';
import { Rng } from './prng';

export const DEFAULT_SEED = 20260912;
/** 2026-09-12T00:00:00Z — the fixed "today" for the demo dataset. */
export const REFERENCE_DATE = Date.UTC(2026, 8, 12);
export const DAYS = 90;
const DAY_MS = 86_400_000;

export interface GeneratorOptions {
  seed?: number;
  referenceDate?: number;
  shops?: number;
  devices?: number;
  callers?: number;
  calls?: number;
  days?: number;
}

// ---------------------------------------------------------------------------
// Vocabularies
// ---------------------------------------------------------------------------

const DISTRICTS: Record<string, Record<string, string[]>> = {
  Rangpur: {
    Pirganj: ['Chatra', 'Bhendabari', 'Kumedpur'],
    Mithapukur: ['Bhangni', 'Payrabond', 'Ranipukur'],
  },
  Kurigram: {
    Ulipur: ['Dharanibari', 'Hatia', 'Buraburi'],
    Chilmari: ['Ranigonj', 'Thanahat'],
  },
  Barishal: {
    Bakerganj: ['Charamaddi', 'Kalaskati', 'Garuria'],
    Banaripara: ['Chakhar', 'Saliabakpur'],
  },
  Sylhet: {
    Bishwanath: ['Daulatpur', 'Lama Kazi'],
    Balaganj: ['Purba Pailanpur', 'Boaljur'],
  },
};

const FIRST_F = ['Rahima', 'Fatema', 'Shirin', 'Nasrin', 'Rokeya', 'Ayesha', 'Salma', 'Momena', 'Jesmin', 'Hasina', 'Rina', 'Sultana'];
const FIRST_M = ['Abdul', 'Rafiq', 'Jamal', 'Karim', 'Habib', 'Monir', 'Selim', 'Faruk', 'Nazrul', 'Shahid', 'Belal', 'Anwar'];
const LAST = ['Begum', 'Khatun', 'Akter', 'Islam', 'Hossain', 'Rahman', 'Mia', 'Sarkar', 'Ali', 'Uddin', 'Sheikh', 'Molla'];

const OCCUPATIONS = ['farmer', 'day labourer', 'homemaker', 'shopkeeper', 'student', 'rickshaw puller', 'tailor', 'fisher', 'teacher'];

const SYMPTOMS = ['fever', 'cough', 'headache', 'abdominal pain', 'diarrhoea', 'chest pain', 'shortness of breath', 'dizziness', 'joint pain', 'skin rash', 'vomiting', 'weakness', 'back pain', 'sore throat'];
const CONDITIONS = ['hypertension', 'type 2 diabetes', 'asthma', 'gastritis', 'anaemia', 'arthritis', 'chronic kidney disease'];
const MEDICATIONS = ['paracetamol', 'metformin', 'amlodipine', 'omeprazole', 'salbutamol inhaler', 'ORS', 'iron tablets', 'cetirizine', 'azithromycin'];
const ALLERGIES = ['penicillin', 'sulfa drugs', 'dust', 'shrimp', 'NSAIDs'];
const ADVICE = ['rest and fluids', 'visit upazila health complex', 'ORS for dehydration', 'monitor blood pressure', 'take prescribed medication', 'seek emergency care', 'dietary advice', 'follow-up in 3 days'];
const RISK_FLAGS = ['possible dengue', 'uncontrolled hypertension', 'severe dehydration', 'possible pneumonia', 'pregnancy complication', 'chest pain — cardiac?', 'high fever in child'];
const REFERRALS = ['upazila health complex', 'district hospital', 'community clinic', 'emergency (call 999)', 'pharmacy consult'];

const FOLLOW_UP_REASONS = ['Fever not resolved after 3 days', 'Blood pressure reading pending', 'Child under 5 with high fever', 'Referral outcome unknown', 'Medication adherence check', 'Dehydration follow-up', 'Chest pain reported — confirm hospital visit'];

const END_REASONS_FAIL = ['gemini_ws_closed', 'network_timeout', 'audio_route_lost', 'quota_exceeded', 'injector_stall'];
const DEVICE_ERRORS = ['Route B inject: underrun x3', 'WebSocket 1006 during call', 'SIM registration lost 14:02', 'Battery 9% — throttled', 'Capture buffer overflow'];
const SUMMARIZER_ERRORS = ['profile summarizer: bad JSON', 'profile summarizer: HTTP 429', 'profile summarizer: timeout'];

const CALLER_LINES = [
  'আমার তিন দিন ধরে জ্বর, মাথা ব্যথা করছে।',
  'বাচ্চার পাতলা পায়খানা হচ্ছে, কী করব?',
  'বুকে ব্যথা করছে, শ্বাস নিতে কষ্ট হচ্ছে।',
  'প্রেসারের ওষুধ শেষ হয়ে গেছে।',
  'কাশি কমছে না, রাতে বেশি হয়।',
  'হাত-পা দুর্বল লাগে, মাথা ঘোরে।',
];
const ASSISTANT_LINES = [
  'বুঝতে পেরেছি। জ্বর কত দিন ধরে? তাপমাত্রা মেপেছেন?',
  'বাচ্চাকে ঘন ঘন ওরস্যালাইন খাওয়ান। প্রস্রাব কম হলে আজই স্বাস্থ্য কমপ্লেক্সে যান।',
  'বুকে ব্যথা ও শ্বাসকষ্ট গুরুতর হতে পারে। দয়া করে এখনই জরুরি সেবায় যান।',
  'ওষুধ বন্ধ করবেন না। নিকটস্থ কমিউনিটি ক্লিনিকে গিয়ে প্রেসার মাপান।',
  'কাশির সাথে কফ বা রক্ত আছে কি? তিন দিনের বেশি হলে ডাক্তার দেখান।',
  'পর্যাপ্ত বিশ্রাম নিন এবং পানি খান। তিন দিনে না কমলে আবার ফোন করুন।',
];

// ---------------------------------------------------------------------------

function pad(n: number, w = 4): string {
  return String(n).padStart(w, '0');
}

export function generateSnapshot(opts: GeneratorOptions = {}): Snapshot {
  const seed = opts.seed ?? DEFAULT_SEED;
  const ref = opts.referenceDate ?? REFERENCE_DATE;
  const days = opts.days ?? DAYS;
  const nShops = opts.shops ?? 10;
  const nDevices = opts.devices ?? 12;
  const nCallers = opts.callers ?? 300;
  const nCalls = opts.calls ?? 1000;
  const rng = new Rng(seed);
  const start = ref - days * DAY_MS;

  const organization = { id: 'org-1', name: 'CallBridge Pilot' };

  // Shops -------------------------------------------------------------------
  const districtNames = Object.keys(DISTRICTS);
  const shops: Shop[] = [];
  const shopkeepers: Shopkeeper[] = [];
  for (let i = 0; i < nShops; i++) {
    const district = districtNames[i % districtNames.length];
    const upazilas = Object.keys(DISTRICTS[district]);
    const upazila = upazilas[Math.floor(i / districtNames.length) % upazilas.length];
    const village = rng.pick(DISTRICTS[district][upazila]);
    const skName = `${rng.pick(rng.chance(0.3) ? FIRST_F : FIRST_M)} ${rng.pick(LAST)}`;
    const sk: Shopkeeper = {
      id: `sk-${pad(i + 1, 2)}`,
      name: skName,
      phone: `017${pad(rng.int(10_000_000, 99_999_999), 8)}`,
      shopId: `shop-${pad(i + 1, 2)}`,
      active: true,
    };
    shopkeepers.push(sk);
    shops.push({
      id: sk.shopId!,
      orgId: organization.id,
      name: `${village} ${rng.pick(['Pharmacy', 'Telecom', 'Store', 'Bazar Point'])}`,
      district,
      upazila,
      village,
      shopkeeperId: sk.id,
      createdAt: start - rng.int(10, 120) * DAY_MS,
    });
  }
  // One spare, unassigned shopkeeper for the admin page.
  shopkeepers.push({ id: 'sk-99', name: `${rng.pick(FIRST_M)} ${rng.pick(LAST)}`, phone: `018${pad(rng.int(10_000_000, 99_999_999), 8)}`, shopId: null, active: false });

  // Devices ------------------------------------------------------------------
  const devices: Device[] = [];
  for (let i = 0; i < nDevices; i++) {
    const shop = shops[i % shops.length];
    const offline = i === 3 || i === 9; // deterministic offline devices
    const syncError = i === 6;
    devices.push({
      id: `dev-${pad(i + 1, 2)}`,
      shopId: shop.id,
      label: `SM-G781B #${i + 1}`,
      appVersion: rng.chance(0.75) ? '0.3.1' : '0.3.0',
      simId: `8801${pad(rng.int(100_000_000, 999_999_999), 9)}`,
      lastSeenAt: offline ? ref - rng.int(2, 6) * DAY_MS : ref - rng.int(1, 240) * 60_000,
      lastCallAt: null,
      syncState: syncError ? 'ERROR' : rng.chance(0.15) ? 'PENDING' : 'SYNCED',
      status: offline ? 'OFFLINE' : 'ONLINE',
      recentErrors: offline || syncError ? rng.sample(DEVICE_ERRORS, rng.int(1, 3)) : rng.chance(0.3) ? [rng.pick(DEVICE_ERRORS)] : [],
    });
  }
  const devicesByShop = new Map<string, Device[]>();
  for (const d of devices) {
    if (!d.shopId) continue;
    devicesByShop.set(d.shopId, [...(devicesByShop.get(d.shopId) ?? []), d]);
  }

  // Callers ------------------------------------------------------------------
  const callers: Caller[] = [];
  const callerSex = new Map<string, Sex>();
  const callerAge = new Map<string, number | null>();
  const callerShop = new Map<string, Shop>();
  const usedNumbers = new Set<string>();
  for (let i = 0; i < nCallers; i++) {
    let number: string;
    do {
      number = `01${rng.pick(['3', '5', '6', '7', '8', '9'])}${pad(rng.int(10_000_000, 99_999_999), 8)}`;
    } while (usedNumbers.has(number));
    usedNumbers.add(number);
    const sex: Sex = rng.weighted(['F', 'M', 'UNKNOWN'], [52, 44, 4]);
    const shop = rng.pick(shops);
    const village = rng.chance(0.85) ? (rng.chance(0.7) ? shop.village : rng.pick(DISTRICTS[shop.district][shop.upazila])) : null;
    const first = sex === 'F' ? rng.pick(FIRST_F) : rng.pick(FIRST_M);
    callers.push({
      number,
      name: rng.chance(0.9) ? `${first} ${rng.pick(LAST)}` : null,
      village,
      occupation: rng.chance(0.8) ? rng.pick(OCCUPATIONS) : null,
      languageNote: rng.chance(0.1) ? 'Sylheti dialect' : null,
      registeredByShop: shop.id,
      createdAt: start + rng.int(-30, days - 1) * DAY_MS,
    });
    callerSex.set(number, sex);
    callerAge.set(number, rng.chance(0.85) ? Math.max(1, Math.round(rng.gauss(38, 17))) : null);
    callerShop.set(number, shop);
  }

  // Calls ------------------------------------------------------------------
  // Weekday curve + slight upward trend so charts look real.
  const dayWeights: number[] = [];
  for (let d = 0; d < days; d++) {
    const dow = new Date(start + d * DAY_MS).getUTCDay();
    const wk = dow === 5 ? 0.6 : dow === 6 ? 0.8 : 1; // Fri/Sat lighter
    dayWeights.push(wk * (0.7 + (0.6 * d) / days));
  }
  const dayIdx = Array.from({ length: days }, (_, i) => i);

  // Repeat-caller skew: ~30% of callers make most of the calls.
  const heavy = rng.sample(callers, Math.round(nCallers * 0.3)).map((c) => c.number);
  const light = callers.filter((c) => !heavy.includes(c.number)).map((c) => c.number);

  const calls: Call[] = [];
  const turns: Turn[] = [];
  const observations: Observation[] = [];
  const followUps: FollowUp[] = [];
  const profileUpdates: ProfileUpdate[] = [];
  const perCaller = new Map<string, Call[]>();

  for (let i = 0; i < nCalls; i++) {
    const number = rng.chance(0.65) ? rng.pick(heavy) : rng.pick(light);
    const shop = callerShop.get(number)!;
    const device = rng.pick(devicesByShop.get(shop.id) ?? devices);
    const d = rng.weighted(dayIdx, dayWeights);
    const startedAt = start + d * DAY_MS + rng.int(7, 21) * 3_600_000 + rng.int(0, 3599) * 1000;
    if (startedAt > ref) continue;
    const isCallback = rng.chance(0.1);
    const outcome: CallOutcome = rng.weighted(['COMPLETED', 'FAILED', 'NO_ANSWER', 'HUNG_UP'], [78, 12, isCallback ? 6 : 2, 6]);
    const completed = outcome === 'COMPLETED';
    const durationSec = completed ? Math.max(25, Math.round(rng.gauss(170, 70))) : outcome === 'NO_ANSWER' ? 0 : rng.int(3, 60);
    const inputSeconds = durationSec * 0.55;
    const outputSeconds = durationSec * 0.4;
    // Gemini Live audio pricing approximation: in $0.00006/s, out $0.00015/s (mocked).
    const estCostUsd = +(inputSeconds * 0.00006 + outputSeconds * 0.00015 + 0.002).toFixed(4);
    const risk: RiskLevel = completed ? rng.weighted(['NONE', 'LOW', 'MEDIUM', 'HIGH'], [55, 25, 14, 6]) : 'NONE';
    const hasTranscript = completed ? rng.chance(0.86) : false;
    const summarizationError = completed && hasTranscript && rng.chance(0.06) ? rng.pick(SUMMARIZER_ERRORS) : null;
    const call: Call = {
      id: `call-${pad(i + 1)}`,
      number,
      shopId: shop.id,
      deviceId: device.id,
      direction: isCallback ? 'OUTBOUND_CALLBACK' : 'INBOUND',
      startedAt,
      endedAt: outcome === 'NO_ANSWER' ? null : startedAt + durationSec * 1000,
      endReason: outcome === 'FAILED' ? rng.pick(END_REASONS_FAIL) : outcome === 'HUNG_UP' ? 'caller_hangup' : outcome === 'NO_ANSWER' ? 'no_answer' : 'assistant_goodbye',
      outcome,
      inputSeconds: +inputSeconds.toFixed(1),
      outputSeconds: +outputSeconds.toFixed(1),
      estCostUsd,
      latencyMs: Math.max(180, Math.round(rng.gauss(650, 220))),
      hasTranscript,
      summarizationError,
      risk,
      reviewState: risk === 'HIGH' && rng.chance(0.4) ? 'FLAGGED' : rng.chance(0.2) ? 'REVIEWED' : 'UNREVIEWED',
      adminNote: null,
    };
    calls.push(call);
    perCaller.set(number, [...(perCaller.get(number) ?? []), call]);
    if (!device.lastCallAt || device.lastCallAt < startedAt) device.lastCallAt = startedAt;

    if (hasTranscript) {
      const n = rng.int(3, 7);
      for (let t = 0; t < n; t++) {
        const role = t % 2 === 0 ? 'CALLER' : 'ASSISTANT';
        turns.push({
          id: `turn-${call.id}-${t}`,
          callId: call.id,
          role,
          text: role === 'CALLER' ? rng.pick(CALLER_LINES) : rng.pick(ASSISTANT_LINES),
          tMs: Math.round((t / n) * durationSec * 1000),
        });
      }
    }

    if (completed) {
      const firstTurn = hasTranscript ? `turn-${call.id}-0` : null;
      const addObs = (category: string, label: string, conf = rng.float() * 0.35 + 0.6) =>
        observations.push({
          id: `obs-${pad(observations.length + 1, 5)}`,
          callId: call.id,
          number,
          at: startedAt,
          pack: 'health',
          category,
          code: label.toLowerCase().replace(/[^a-z0-9]+/g, '_'),
          label,
          confidence: +conf.toFixed(2),
          evidenceTurnId: firstTurn,
        });
      for (const s of rng.sample(SYMPTOMS, rng.int(1, 3))) addObs('symptom', s);
      if (rng.chance(0.35)) addObs('condition', rng.pick(CONDITIONS));
      if (rng.chance(0.4)) addObs('medication', rng.pick(MEDICATIONS));
      if (rng.chance(0.08)) addObs('allergy', rng.pick(ALLERGIES));
      for (const a of rng.sample(ADVICE, rng.int(1, 2))) addObs('advice', a);
      if (risk === 'MEDIUM' || risk === 'HIGH') addObs('risk', rng.pick(RISK_FLAGS));
      if (risk === 'HIGH' || (risk === 'MEDIUM' && rng.chance(0.4))) addObs('referral', rng.pick(REFERRALS));
      // Some unclassified observations (data quality metric).
      if (rng.chance(0.05)) addObs('unclassified', rng.pick(['unclear complaint', 'non-health query', 'noise']), 0.3);

      if (rng.chance(risk === 'HIGH' ? 0.9 : risk === 'MEDIUM' ? 0.45 : 0.06)) {
        const dueAt = startedAt + rng.int(1, 7) * DAY_MS;
        const state: FollowUpState = dueAt < ref - 5 * DAY_MS ? rng.weighted<FollowUpState>(['COMPLETED', 'REVIEWED', 'PENDING'], [60, 20, 20]) : rng.weighted<FollowUpState>(['PENDING', 'REVIEWED', 'COMPLETED'], [65, 20, 15]);
        followUps.push({
          id: `fu-${pad(followUps.length + 1)}`,
          number,
          callId: call.id,
          shopId: shop.id,
          reason: rng.pick(FOLLOW_UP_REASONS),
          risk: risk === 'NONE' ? 'LOW' : risk,
          createdAt: startedAt,
          dueAt,
          state,
          notes: state === 'PENDING' ? [] : [{ at: dueAt, actor: 'admin', text: state === 'COMPLETED' ? 'Caller reached, advised.' : 'Reviewed, awaiting callback.' }],
        });
      }
    }
  }
  calls.sort((a, b) => a.startedAt - b.startedAt);

  // Profiles -----------------------------------------------------------------
  const profiles: PatientProfile[] = [];
  for (const c of callers) {
    const mine = perCaller.get(c.number) ?? [];
    const completed = mine.filter((x) => x.outcome === 'COMPLETED' && x.hasTranscript);
    if (!completed.length || rng.chance(0.08)) continue; // missing profiles
    const obs = observations.filter((o) => o.number === c.number);
    const by = (cat: string) => [...new Set(obs.filter((o) => o.category === cat).map((o) => o.label))];
    const riskFlags = by('risk');
    const last = completed[completed.length - 1];
    const lastError = last.summarizationError;
    const p: PatientProfile = {
      number: c.number,
      displayName: c.name,
      ageYears: callerAge.get(c.number) ?? null,
      sex: callerSex.get(c.number) ?? 'UNKNOWN',
      village: c.village,
      chronicConditions: by('condition'),
      currentSymptoms: by('symptom').slice(0, 4),
      medications: by('medication'),
      allergies: by('allergy'),
      riskFlags,
      adviceGiven: by('advice').slice(0, 4),
      followUpNeeded: followUps.some((f) => f.number === c.number && f.state === 'PENDING'),
      followUpNote: null,
      summaryBn: 'রোগীর সাম্প্রতিক কলগুলোর সারসংক্ষেপ (AI-উৎপন্ন, চিকিৎসকের যাচাই প্রয়োজন)।',
      summaryEn: `AI-generated summary across ${completed.length} call(s). Reported ${by('symptom').slice(0, 2).join(', ') || 'no symptoms'}${riskFlags.length ? `; flags: ${riskFlags.join(', ')}` : ''}. Requires clinician review.`,
      lastUpdated: last.startedAt + 60_000,
      callCount: mine.length,
      lastError,
    };
    profiles.push(p);
    completed.forEach((call, i) => {
      if (call.summarizationError) return;
      const o = observations.filter((x) => x.callId === call.id);
      profileUpdates.push({
        id: `pu-${pad(profileUpdates.length + 1)}`,
        number: c.number,
        callId: call.id,
        timestamp: call.startedAt + 45_000,
        deltaSummary: i === 0 ? `Profile created. ${o.length} observation(s) recorded.` : `Added ${o.filter((x) => x.category === 'symptom').map((x) => x.label).join(', ') || 'no new symptoms'}${o.some((x) => x.category === 'risk') ? '; new risk flag' : ''}.`,
      });
    });
  }

  const audit: AuditEntry[] = [
    { id: 'aud-0001', at: ref - 3 * DAY_MS, actor: 'admin', action: 'ASSIGN_DEVICE', target: 'dev-11 → shop-01' },
    { id: 'aud-0002', at: ref - 2 * DAY_MS, actor: 'admin', action: 'EXPORT_CSV', target: 'calls (30d)' },
    { id: 'aud-0003', at: ref - 1 * DAY_MS, actor: 'admin', action: 'UPDATE_SHOP', target: 'shop-04' },
  ];

  return {
    generatedAt: ref,
    referenceDate: ref,
    isDemo: true,
    organization,
    shops,
    shopkeepers,
    devices,
    callers,
    calls,
    turns,
    profiles,
    profileUpdates,
    observations,
    followUps,
    audit,
  };
}
