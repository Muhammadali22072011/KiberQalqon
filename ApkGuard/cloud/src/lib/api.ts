// API mijozi. Brauzerda master kalit/parol hech qachon saqlanmaydi — faqat qisqa
// muddatli sessiya tokeni. Ikki xil foydalanuvchi bor:
//   • egasi (owner) → master kalit (+2FA) bilan kiradi. To'liq huquq.
//   • admin         → login+parol bilan kiradi. Faqat ko'rish + eksport + e'lon.
// Ikkalasining ham tokeni x-admin-secret sarlavhasida ketadi; server qaysi biri
// ekanini token ichidan biladi. 401 bo'lsa — sessiya tozalanadi va onUnauth chaqiriladi.

const KEY = 'kq_session';

export type Kind = 'owner' | 'admin';
export interface Session {
  token: string;
  kind: Kind;
  name?: string;
  exp?: number; // unix soniya
}

function readStored(): Session | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    if (!raw) return null;
    const s = JSON.parse(raw) as Session;
    return s && typeof s.token === 'string' && s.token ? s : null;
  } catch {
    return null;
  }
}

let session: Session | null = readStored();

let onUnauth: (() => void) | null = null;
export function setUnauthHandler(fn: (() => void) | null) { onUnauth = fn; }

export const getSession = (): Session | null => session;
export function setSession(s: Session): void {
  session = s;
  sessionStorage.setItem(KEY, JSON.stringify(s));
}
export function clearSession(): void {
  session = null;
  sessionStorage.removeItem(KEY);
}
export const hasToken = (): boolean => Boolean(session?.token);

export interface ApiError extends Error { auth?: boolean; }

async function request<T = any>(path: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = {
    ...(init?.headers as Record<string, string> | undefined),
  };
  if (session?.token) {
    headers['x-admin-secret'] = session.token;
  }
  let r: Response;
  try {
    r = await fetch(path, { ...init, headers });
  } catch {
    throw new Error('tarmoq');
  }
  if (r.status === 401) {
    clearSession();
    onUnauth?.();
    const e = new Error('auth') as ApiError;
    e.auth = true;
    throw e;
  }
  const j = await r.json().catch(() => ({ ok: false, error: 'json' }));
  if (!j || !j.ok) throw new Error((j && j.error) || 'xato');
  return j as T;
}

export const apiGet = <T = any>(path: string) => request<T>(path, { method: 'GET' });
export const apiPost = <T = any>(path: string, body: unknown) =>
  request<T>(path, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });

// Egasi (owner) kirishi: master kalit + (yoqilgan bo'lsa) 2FA. To'liq huquq.
export interface LoginResult { ok: boolean; token: string; exp?: number; twofa?: boolean; }
export async function login(secret: string, otp: string): Promise<LoginResult> {
  const r = await fetch('/api/admin/login', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ secret, otp }),
  });
  const j = await r.json().catch(() => ({ ok: false }));
  if (!r.ok || !j.ok) throw new Error((j && j.error) || 'Kalit yoki kod noto‘g‘ri');
  return j as LoginResult;
}

// Admin kirishi: login + parol. Cheklangan — faqat ko'rish + eksport + e'lon.
export interface AdminLoginResult {
  ok: boolean; token: string; exp?: number; level: 'admin'; name?: string;
}
export async function adminLogin(login: string, password: string): Promise<AdminLoginResult> {
  const r = await fetch('/api/admin/login', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ login, password }),
  });
  const j = await r.json().catch(() => ({ ok: false }));
  if (!r.ok || !j.ok || !j.token) throw new Error((j && j.error) || 'Login yoki parol noto‘g‘ri');
  return j as AdminLoginResult;
}

// ── Javob turlari (Supabase view'lariga mos) ────────────────────────────────
export interface Stats {
  total_scans?: number; danger_count?: number; suspicious_count?: number;
  safe_count?: number; active_devices?: number;
  // Tekshiruv tezligi (v_stats_today'dan; faqat o'lchangan skanlar)
  perf_count?: number; avg_duration_ms?: number; median_duration_ms?: number; p95_duration_ms?: number;
}
export interface ScanPerf {
  median_ms: number; p95_ms: number; avg_ms: number; count: number;
  slowest: Array<{ app_label?: string | null; package_name?: string | null; apk_size?: number | null; duration_ms: number }>;
}
export interface MapPoint {
  id: string; name?: string | null; city?: string | null; country?: string | null; ip?: string | null;
  lat: number | null; lng: number | null; risk_score?: number; last_verdict?: string | null;
  last_seen?: string | null; last_scan_at?: string | null; scan_count?: number; danger_count?: number;
}
export interface FeedItem {
  scanned_at: string; device_name?: string | null; app_label?: string | null;
  package_name?: string | null; apk_hash?: string | null; verdict: string;
  reasons?: unknown; city?: string | null; risk_score?: number | null;
}
export interface ThreatFamily {
  apk_hash: string; package_name?: string | null; app_label?: string | null;
  category?: string | null; severity?: string | null; seen_count?: number;
  first_seen?: string; last_seen?: string;
  sample_url?: string | null; // APK namunasini yuklab olish uchun imzolangan URL (bor bo'lsa)
}
export interface DeviceRow {
  id: string; name?: string | null; android_ver?: string | null; app_ver?: string | null;
  created_at?: string; last_seen?: string; country?: string | null; city?: string | null; ip?: string | null;
  lat?: number | null; lng?: number | null; risk_score?: number; last_verdict?: string | null;
  last_scan_at?: string | null; scan_count?: number; danger_count?: number;
}
export interface ScanRow {
  id: number; apk_hash?: string; package_name?: string | null; app_label?: string | null;
  verdict: string; risk_score?: number; reasons?: unknown; scanned_at?: string;
}
export interface NewsItem {
  id: string; title: string; body?: string | null; level?: string;
  image_url?: string | null; pinned?: boolean; created_at: string;
}
export interface AuditRow {
  id: number; at: string; actor: string; action: string;
  detail?: string | null; ip?: string | null;
}
export interface ThreatDomain {
  domain: string; category?: string | null; severity?: string | null;
  source?: string | null; first_seen?: string; last_seen?: string;
}
export interface AppUpdateInfo {
  versionCode: number; apkUrl: string; apkSha256: string;
}
