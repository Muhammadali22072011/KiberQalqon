// Umumiy formatlash yordamchilari (eski paneldan ko'chirildi, React uchun moslandi).

export const fmt = (n: number): string => Math.round(n || 0).toLocaleString('ru-RU');

const MONTHS = [
  'yanvar', 'fevral', 'mart', 'aprel', 'may', 'iyun',
  'iyul', 'avgust', 'sentabr', 'oktabr', 'noyabr', 'dekabr',
];

export const uzDate = (d: Date): string =>
  `${d.getDate()}-${MONTHS[d.getMonth()]} ${d.getFullYear()}`;

export function uzDateSafe(s?: string | null): string {
  if (!s) return '—';
  const d = new Date(s);
  return isNaN(d.getTime()) ? '—' : uzDate(d);
}

export function ago(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) ms = 0;
  const m = Math.floor(ms / 60000);
  if (m < 1) return 'hozirgina';
  if (m < 60) return `${m} daqiqa oldin`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h} soat oldin`;
  return `${Math.floor(h / 24)} kun oldin`;
}

export function agoSafe(s?: string | null): string {
  if (!s) return '—';
  const t = new Date(s).getTime();
  if (!Number.isFinite(t)) return '—';
  return ago(Date.now() - t);
}

export function hms(s?: string | null): string {
  const d = s ? new Date(s) : new Date();
  if (isNaN(d.getTime())) return '--:--:--';
  return [d.getHours(), d.getMinutes(), d.getSeconds()]
    .map((x) => String(x).padStart(2, '0'))
    .join(':');
}

export type Verdict = 'danger' | 'suspicious' | 'safe' | 'error';

export const VERDICT_UZ: Record<string, string> = {
  danger: 'Xavfli', suspicious: 'Shubhali', safe: 'Xavfsiz', error: 'Xatolik',
};
export const VERDICT_BG: Record<string, string> = {
  danger: '#FBE0DB', suspicious: '#FBECD2',
  safe: '#E1F4E7', error: '#EFE9E0',
};
export const VERDICT_FG: Record<string, string> = {
  danger: '#911C10', suspicious: '#84500C', safe: '#0C6135', error: '#685C52',
};
export const VERDICT_DOT: Record<string, string> = {
  danger: '#E0432F', suspicious: '#DF8A18', safe: '#1A9E54', error: '#9A8D82',
};
export const SEV_COLOR: Record<string, string> = {
  critical: '#911C10', high: '#E0432F', medium: '#DF8A18', low: '#1A9E54',
};
export const CAT_UZ: Record<string, string> = {
  sms_stealer: "SMS o'g'risi", spyware: 'Josus dastur', dropper: 'Yuklovchi (dropper)',
  trojan: 'Troyan', banker: 'Bank troyani', suspicious: 'Shubhali ilova',
};
export const catUz = (c?: string | null): string => (c ? CAT_UZ[c] || c : '—');

export const scoreVerdict = (s: number): Verdict =>
  s >= 62 ? 'danger' : s >= 32 ? 'suspicious' : 'safe';

// Xavf bali → rang (yashildan qizilgacha).
export const riskColor = (s: number): string => {
  const v = Math.max(0, Math.min(100, s || 0));
  return `hsl(${(150 * (1 - v / 100)).toFixed(0)},70%,40%)`;
};
