// API mijozi. Brauzerda ADMIN_SECRET emas, faqat qisqa muddatli sessiya tokeni
// (x-admin-secret) saqlanadi. 401 bo'lsa — token tozalanadi va onUnauth chaqiriladi.

const KEY = 'kq_admin_secret';

let onUnauth: (() => void) | null = null;
export function setUnauthHandler(fn: (() => void) | null) { onUnauth = fn; }

export const getToken = (): string => sessionStorage.getItem(KEY) || '';
export const setToken = (t: string): void => sessionStorage.setItem(KEY, t);
export const clearToken = (): void => sessionStorage.removeItem(KEY);

export interface ApiError extends Error { auth?: boolean; }

async function request<T = any>(path: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = {
    ...(init?.headers as Record<string, string> | undefined),
    'x-admin-secret': getToken(),
  };
  let r: Response;
  try {
    r = await fetch(path, { ...init, headers });
  } catch {
    throw new Error('tarmoq');
  }
  if (r.status === 401) {
    clearToken();
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

// ── Javob turlari (Supabase view'lariga mos) ────────────────────────────────
export interface Stats {
  total_scans?: number; danger_count?: number; suspicious_count?: number;
  safe_count?: number; active_devices?: number;
}
export interface MapPoint {
  id: string; name?: string | null; city?: string | null; country?: string | null;
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
}
export interface DeviceRow {
  id: string; name?: string | null; android_ver?: string | null; app_ver?: string | null;
  created_at?: string; last_seen?: string; country?: string | null; city?: string | null;
  lat?: number | null; lng?: number | null; risk_score?: number; last_verdict?: string | null;
  last_scan_at?: string | null; scan_count?: number; danger_count?: number;
}
export interface ScanRow {
  id: number; apk_hash?: string; package_name?: string | null; app_label?: string | null;
  verdict: string; risk_score?: number; reasons?: unknown; scanned_at?: string;
}
export interface Role {
  id?: string; name: string; permissions?: string[]; components?: string[]; created_at?: string;
}
export interface Operator {
  id?: string; login: string; active?: boolean; created_at?: string;
  last_login_at?: string | null; role_name?: string | null;
  permissions?: string[]; components?: string[];
}
export interface NewsItem {
  id: string; title: string; body?: string | null; level?: string;
  image_url?: string | null; pinned?: boolean; created_at: string;
}
