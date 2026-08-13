import type * as XLSXNS from 'xlsx';
import {
  apiGet, type Stats, type DeviceRow, type ThreatFamily, type FeedItem, type MapPoint,
  type GroupMember, type TgRegStats,
} from './api';
import { nearestCity } from './uzRegions';
import { VERDICT_UZ, catUz } from './format';

// Excel'da xom enum chiqmasin (verdict 'danger', severity 'high'): o'zbekcha nomlar.
const SEV_UZ: Record<string, string> = { critical: 'Juda yuqori', high: 'Yuqori', medium: "O'rta", low: 'Past' };
const sevUz = (s?: string | null): string => (s ? SEV_UZ[s] || s : '—');
const verdictUz = (v?: string | null): string => (v ? VERDICT_UZ[v] || v : '—');

// Panel ma'lumotlarini bitta Excel (.xlsx) faylga eksport qiladi — har bo'lim alohida
// varaq (list). Faqat o'qish endpointlaridan oladi (egasi ham, admin ham eksport qila oladi).
// Brauzer faylni yuklab oladi. SheetJS faqat YOZISH uchun ishlatiladi (o'z ma'lumotimiz).
// xlsx dinamik import qilinadi — faqat eksport bosilganda yuklanadi (asosiy bandl yengil qoladi).

function sheet(XLSX: typeof XLSXNS, rows: Record<string, unknown>[]): XLSXNS.WorkSheet {
  return XLSX.utils.json_to_sheet(rows.length ? rows : [{ '—': "ma'lumot yo'q" }]);
}

export async function exportAllToExcel(): Promise<void> {
  const XLSX = await import('xlsx');
  // Har bir endpoint xatosini alohida ushlaymiz, lekin HAMMASI yiqilsa — throw qilamiz, aks holda
  // Layout "Excel fayl tayyor" deb bo'sh fayl bergan bo'lardi (jim muvaffaqiyat = yolg'on).
  let okCount = 0;
  const ok = <T,>(p: Promise<T>, fallback: T): Promise<T> =>
    p.then((v) => { okCount++; return v; }).catch(() => fallback);
  const [statsR, devicesR, threatsR, feedR, geoR, membersR, tgregR] = await Promise.all([
    ok(apiGet<{ stats: Stats }>('/api/stats'), { stats: {} as Stats }),
    ok(apiGet<{ devices: DeviceRow[] }>('/api/devices'), { devices: [] as DeviceRow[] }),
    ok(apiGet<{ threats: ThreatFamily[] }>('/api/threats'), { threats: [] as ThreatFamily[] }),
    ok(apiGet<{ feed: FeedItem[] }>('/api/feed'), { feed: [] as FeedItem[] }),
    ok(apiGet<{ points: MapPoint[] }>('/api/geo'), { points: [] as MapPoint[] }),
    ok(apiGet<{ members: GroupMember[] }>('/api/devices?members=1'), { members: [] as GroupMember[] }),
    ok(apiGet<{ tgreg: TgRegStats }>('/api/stats?tgreg=1'), { tgreg: null as TgRegStats | null }),
  ]);
  if (okCount === 0) throw new Error('export: barcha endpointlar xato');

  const s = statsR.stats || {};
  const statsRows = [
    { "Ko'rsatkich": 'Bugungi skanlar', Qiymat: s.total_scans ?? 0 },
    { "Ko'rsatkich": 'Xavfli', Qiymat: s.danger_count ?? 0 },
    { "Ko'rsatkich": 'Shubhali', Qiymat: s.suspicious_count ?? 0 },
    { "Ko'rsatkich": 'Xavfsiz', Qiymat: s.safe_count ?? 0 },
    { "Ko'rsatkich": 'Faol qurilmalar', Qiymat: s.active_devices ?? 0 },
  ];

  const deviceRows = (devicesR.devices || []).map((d) => ({
    Nomi: d.name ?? '',
    Guruh: d.group_name ?? '',
    'A‘zo': [d.member_first, d.member_last].filter(Boolean).join(' '),
    Telefon: d.member_phone ?? '',
    Shahar: nearestCity(d.lat, d.lng) ?? d.city ?? '',
    Mamlakat: d.country ?? '',
    Android: d.android_ver ?? '',
    Ilova: d.app_ver ?? '',
    'Xavf bali': d.risk_score ?? 0,
    Skanlar: d.scan_count ?? 0,
    Xavflilar: d.danger_count ?? 0,
    "Oxirgi ko'rinish": d.last_seen ?? '',
  }));

  const threatRows = (threatsR.threats || []).map((t) => ({
    Ilova: t.app_label ?? t.package_name ?? '',
    Paket: t.package_name ?? '',
    Toifa: catUz(t.category),
    Darajasi: sevUz(t.severity),
    "Ko'rilgan": t.seen_count ?? 0,
    Birinchi: t.first_seen ?? '',
    Oxirgi: t.last_seen ?? '',
    Hash: t.apk_hash ?? '',
  }));

  const feedRows = (feedR.feed || []).map((f) => ({
    Vaqt: f.scanned_at ?? '',
    Ilova: f.app_label ?? f.package_name ?? '',
    Qurilma: f.device_name ?? '',
    Shahar: f.city ?? '',
    Xulosa: verdictUz(f.verdict),
    'Xavf bali': f.risk_score ?? '',
  }));

  const geoRows = (geoR.points || []).map((p) => ({
    Qurilma: p.name ?? '',
    Shahar: nearestCity(p.lat, p.lng) ?? p.city ?? '',
    Mamlakat: p.country ?? '',
    Lat: p.lat ?? '',
    Lng: p.lng ?? '',
    'Xavf bali': p.risk_score ?? 0,
    "So'nggi xulosa": verdictUz(p.last_verdict),
  }));

  // Guruh rostri (a'zolar) — guruh, ism/familiya, telefon + qurilma. Guruhga bo'lingan
  // qurilmalargina (v_group_members). Egasi guruh bo'yicha odamlar ro'yxatini eksport qiladi.
  const memberRows = (membersR.members || []).map((m) => ({
    Guruh: m.group_name ?? '',
    Ism: m.member_first ?? '',
    Familiya: m.member_last ?? '',
    Telefon: m.member_phone ?? '',
    Qurilma: m.device_name ?? '',
    Shahar: m.city ?? '',
    'Xavf bali': m.risk_score ?? 0,
    Skanlar: m.scan_count ?? 0,
    Xavflilar: m.danger_count ?? 0,
    "Oxirgi ko'rinish": m.last_seen ?? '',
  }));

  // Telegram ro'yxatidan o'tgan ODAMLAR (rassilka bazasi). Telefon raqami cheklangan
  // admin uchun serverda niqoblanadi — bu yerda ham niqoblangan holda tushadi.
  const tg = tgregR.tgreg;
  const userRows = (tg?.recent ?? []).map((u) => ({
    Ism: u.full_name ?? '',
    Telegram: u.tg_username ? `@${u.tg_username}` : '',
    Telefon: u.phone ?? '',
    "Ro'yxatdan o'tgan": u.done_at ?? '',
    Qurilma: u.has_device ? 'bor' : '',
    Bloklagan: u.blocked ? 'ha' : '',
  }));

  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, sheet(XLSX, statsRows), 'Umumiy');
  XLSX.utils.book_append_sheet(wb, sheet(XLSX, deviceRows), 'Qurilmalar');
  XLSX.utils.book_append_sheet(wb, sheet(XLSX, memberRows), 'Guruhlar');
  XLSX.utils.book_append_sheet(wb, sheet(XLSX, userRows), 'Foydalanuvchilar');
  XLSX.utils.book_append_sheet(wb, sheet(XLSX, threatRows), 'Tahdidlar');
  XLSX.utils.book_append_sheet(wb, sheet(XLSX, feedRows), 'Oqim');
  XLSX.utils.book_append_sheet(wb, sheet(XLSX, geoRows), 'Xarita');

  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  const name = `kiberqalqon-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}.xlsx`;
  XLSX.writeFile(wb, name);
}
