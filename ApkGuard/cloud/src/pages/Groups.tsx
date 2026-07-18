import { useEffect, useMemo, useState } from 'react';
import { usePoll } from '../hooks/usePoll';
import { apiGet, apiPost, type GroupRow, type GroupMember } from '../lib/api';
import { Empty, Panel, PanelHead, Spinner } from '../components/ui';
import { agoSafe } from '../lib/format';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../components/Toast';
import { qrModules } from '../lib/qr';

// Guruh rangi palitrasi (brend + aniq ajraladigan ranglar). Owner color-picker'da tanlaydi.
const PALETTE = [
  '#C2143D', '#E07A1F', '#2A7DE1', '#1F9E54',
  '#7A3FF2', '#0E9AA7', '#B4237A', '#5A6472',
];

// Ilovaga chuqur havola — bu QR'ni istalgan kamera ochsa ham UzGuard "guruhga qo'shilish"
// ekranini ochadi; ilova skaneri esa kodni shu satrdan ajratib oladi. (Manifestda
// uzguard://join intent-filter GroupJoinActivity'ga bog'langan.)
const joinUrl = (code: string) => `uzguard://join?code=${encodeURIComponent(code)}`;

// Rangni quyultirish — QR "ko'z"lari va logotipi oq fonda yaxshi kontrast bersin (guruh rangi
// och bo'lsa ham skanlanadi). f=0.82 → ~18% to'qroq, lekin brend tusi saqlanadi.
function darken(hex: string, f: number): string {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) return hex;
  const v = parseInt(m[1], 16);
  const r = Math.round(((v >> 16) & 255) * f);
  const g = Math.round(((v >> 8) & 255) * f);
  const b = Math.round((v & 255) * f);
  return `#${((1 << 24) | (r << 16) | (g << 8) | b).toString(16).slice(1)}`;
}

const QR_DARK = '#17100F'; // modul rangi — chuqur siyoh (OQ fonda yuqori kontrast → ishonchli skan)

// Brendlangan QR: yumaloq modullar + rangli "ko'z"lar (finder pattern) + markazda qalqon
// logotipi. Fon HAR DOIM oq (kamera to'q-oq fon kutadi) → skanlanishi kafolatlangan. ECC 'H'
// (30% tiklash) markaziy logotip yopgan modullarni ham o'qiydi. accent — guruh rangi (to'qlashtirilgan).
function buildQrSvg(text: string, px: number, accentRaw: string): string {
  const mods = qrModules(text, 'H');
  const n = mods.length;
  const quiet = 4;
  const dim = n + quiet * 2;
  const accent = darken(accentRaw, 0.82);

  // Finder "ko'z"lari alohida (stilizatsiya bilan) chiziladi → data-modullardan chiqarib tashlaymiz.
  const inFinder = (x: number, y: number) =>
    (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7);

  let dots = '';
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      if (mods[y][x] && !inFinder(x, y)) {
        dots += `<rect x="${(x + quiet + 0.04).toFixed(2)}" y="${(y + quiet + 0.04).toFixed(2)}" width="0.92" height="0.92" rx="0.32"/>`;
      }
    }
  }

  const eye = (ox: number, oy: number) =>
    `<rect x="${ox + 0.5}" y="${oy + 0.5}" width="6" height="6" rx="2" fill="none" stroke="${accent}" stroke-width="1"/>` +
    `<rect x="${ox + 2}" y="${oy + 2}" width="3" height="3" rx="1" fill="${accent}"/>`;
  const eyes = eye(quiet, quiet) + eye(quiet + n - 7, quiet) + eye(quiet, quiet + n - 7);

  // Markaziy qalqon logotipi: oq halo + accent qalqon + oq "✓". Modullar ustiga chiziladi
  // (oq halo ularni yopadi); ECC 'H' yo'qolgan modullarni tiklaydi.
  const L = 7.2;
  const cx = dim / 2, cy = dim / 2;
  const s = (L * 0.78) / 24;
  const logo =
    `<rect x="${(cx - L / 2).toFixed(2)}" y="${(cy - L / 2).toFixed(2)}" width="${L}" height="${L}" rx="${(L * 0.3).toFixed(2)}" fill="#fff"/>` +
    `<g transform="translate(${(cx - (L * 0.78) / 2).toFixed(2)} ${(cy - (L * 0.78) / 2).toFixed(2)}) scale(${s.toFixed(4)})">` +
    `<path d="M12 1 L22 4.6 V12 C22 18.4 12 23 12 23 C12 23 2 18.4 2 12 V4.6 Z" fill="${accent}"/>` +
    `<path d="M8.8 12.3 l2.4 2.4 l4.2-4.8" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>` +
    `</g>`;

  return `<svg xmlns="http://www.w3.org/2000/svg" width="${px}" height="${px}" viewBox="0 0 ${dim} ${dim}" shape-rendering="geometricPrecision" role="img">` +
    `<rect width="${dim}" height="${dim}" rx="3" fill="#fff"/>` +
    `<g fill="${QR_DARK}">${dots}</g>${eyes}${logo}</svg>`;
}

function QrModal({ group, onClose }: { group: GroupRow; onClose: () => void }) {
  const url = joinUrl(group.join_code);
  const svg = useMemo(() => buildQrSvg(url, 260, group.color), [url, group.color]);

  // Chop etish — o'zimizning blank oyna (bir xil origin), faqat QR + kod + guruh nomi.
  const doPrint = () => {
    const w = window.open('', '_blank', 'width=420,height=560');
    if (!w) return;
    w.document.write(
      `<!doctype html><html><head><meta charset="utf-8"><title>${group.name}</title>` +
      `<style>body{font-family:system-ui,Arial,sans-serif;text-align:center;padding:32px;margin:0}` +
      `h1{font-size:20px;margin:0 0 4px;color:${group.color}}.code{font:700 34px/1.1 monospace;letter-spacing:4px;margin:16px 0}` +
      `.hint{color:#555;font-size:13px;margin-top:8px}svg{width:300px;height:300px}</style></head><body>` +
      `<h1>${group.name}</h1><div class="hint">UzGuard → «Guruhga qo‘shilish» → skanerlang</div>` +
      `${buildQrSvg(url, 300, group.color)}<div class="code">${group.join_code}</div>` +
      `<div class="hint">yoki shu kodni kiriting</div></body></html>`,
    );
    w.document.close();
    w.focus();
    setTimeout(() => { try { w.print(); } catch { /* foydalanuvchi qo'lda chop etadi */ } }, 250);
  };

  return (
    <>
      <div className="scrim" onClick={onClose} style={{ zIndex: 209 }} />
      <div className="qr-modal" style={{ ['--g' as string]: group.color }}>
        <button className="btn ghost" onClick={onClose} style={{ position: 'absolute', top: 10, right: 10 }}>✕</button>
        <div className="qr-title">
          <span className="qr-dot" />
          <b>{group.name}</b>
        </div>
        <div className="qr-hint">Telefonda: «Guruhga qo‘shilish» → skanerlang yoki kodni kiriting</div>
        <div className="qr-tile" dangerouslySetInnerHTML={{ __html: svg }} />
        <div className="qr-code">{group.join_code}</div>
        <button className="btn" onClick={doPrint} style={{ marginTop: 12 }}>🖨 Chop etish</button>
      </div>
    </>
  );
}

// Guruh a'zolari rostri: kim qo'shilgan (ism/familiya/telefon + qurilma). /api/devices?members=1
// (v_group_members) dan oladi va shu guruh bo'yicha filtrlaydi. Egasi bu yerda odamlarni ko'radi.
function MembersModal({ group, onClose }: { group: GroupRow; onClose: () => void }) {
  const [rows, setRows] = useState<GroupMember[] | null>(null);
  const [err, setErr] = useState('');

  useEffect(() => {
    let alive = true;
    setRows(null); setErr('');
    apiGet<{ members: GroupMember[] }>('/api/devices?members=1')
      .then((r) => { if (alive) setRows((r.members || []).filter((m) => m.group_id === group.id)); })
      .catch((e) => { if (alive) setErr((e as Error).message || 'xato'); });
    return () => { alive = false; };
  }, [group.id]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <>
      <div className="scrim" onClick={onClose} style={{ zIndex: 209 }} />
      <div
        style={{
          position: 'fixed', zIndex: 210, top: '50%', left: '50%', transform: 'translate(-50%,-50%)',
          background: 'var(--elev)', color: 'var(--ink)', border: '1px solid var(--hair-2)',
          borderRadius: 16, padding: 20, width: 'min(620px, 94vw)', maxHeight: '82vh', overflow: 'auto',
          boxShadow: '0 24px 60px rgba(0,0,0,.5)',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 14 }}>
          <span style={{ width: 12, height: 12, borderRadius: 3, background: group.color, display: 'inline-block' }} />
          <b style={{ fontSize: 17, flex: 1 }}>{group.name} · a‘zolar</b>
          <button className="btn ghost" onClick={onClose}>✕</button>
        </div>
        {err ? (
          <Empty>{err}</Empty>
        ) : !rows ? (
          <Spinner label="Yuklanmoqda…" />
        ) : !rows.length ? (
          <Empty>Bu guruhda hali a‘zo yo‘q. Kod {group.join_code} bilan qo‘shilishadi.</Empty>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table>
              <thead>
                <tr>
                  <th className="num">#</th>
                  <th>Ism</th>
                  <th>Familiya</th>
                  <th>Telefon</th>
                  <th>Qurilma</th>
                  <th>Faollik</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((m, i) => (
                  <tr key={m.device_id}>
                    <td className="num" style={{ color: 'var(--ink-3)' }}>{i + 1}</td>
                    <td><b>{m.member_first || '—'}</b></td>
                    <td>{m.member_last || '—'}</td>
                    <td className="mono">{m.member_phone || '—'}</td>
                    <td style={{ color: 'var(--ink-2)', fontSize: 12 }}>{m.device_name || '—'}</td>
                    <td style={{ color: 'var(--ink-3)', fontSize: 12 }}>{agoSafe(m.last_seen)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  );
}

export default function Groups() {
  const { isOwner } = useAuth();
  const { show } = useToast();
  const { data, loading, error, reload } = usePoll(
    () => apiGet<{ groups: GroupRow[] }>('/api/devices?groups=1'),
    30000,
  );
  const groups = data?.groups || [];

  const [name, setName] = useState('');
  const [color, setColor] = useState(PALETTE[0]);
  const [busy, setBusy] = useState(false);
  const [qr, setQr] = useState<GroupRow | null>(null);
  const [mem, setMem] = useState<GroupRow | null>(null);   // a'zolar rostri ochiq guruh
  const totalDevices = useMemo(() => groups.reduce((s, g) => s + (g.device_count || 0), 0), [groups]);

  const create = async () => {
    const n = name.trim();
    if (!n || busy) return;
    setBusy(true);
    try {
      const r = await apiPost<{ group: GroupRow }>('/api/devices', { action: 'create_group', name: n, color });
      setName('');
      show(`«${n}» yaratildi — kod ${r.group.join_code}`);
      reload();
      setQr(r.group);   // darhol QR/kodni ko'rsatamiz
    } catch (e) {
      show(`Yaratib bo‘lmadi: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  const del = async (g: GroupRow) => {
    if (busy) return;
    if (!window.confirm(`«${g.name}» guruhini o‘chirasizmi? Qurilmalar o‘chmaydi — guruhsiz bo‘lib qoladi.`)) return;
    setBusy(true);
    try {
      await apiPost('/api/devices', { action: 'delete_group', id: g.id });
      show(`«${g.name}» o‘chirildi`);
      reload();
    } catch (e) {
      show(`O‘chirib bo‘lmadi: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <div className="page-intro">
        <h1>Guruhlar</h1>
        <p>Qurilmalarni guruhlarga (nom + rang) bo‘ling. Har guruhning kodi/QR‘i bor — foydalanuvchi UzGuard‘da kod kiritib yoki QR skanerlab qo‘shiladi.</p>
      </div>

      {isOwner && (
        <Panel>
          <PanelHead sub="Yangi guruh" title="Guruh yaratish" />
          <div className="body-pad">
            <div className="row-inline" style={{ flexWrap: 'wrap', gap: 10 }}>
              <input
                placeholder="Guruh nomi: masalan «Navoiy maktabi»"
                value={name}
                onChange={(e) => setName(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') create(); }}
                maxLength={40}
                style={{ flex: '1 1 240px' }}
              />
              <button className="btn" onClick={create} disabled={busy || !name.trim()}>
                {busy ? <span className="spinner" /> : '+ Yaratish'}
              </button>
            </div>
            <div style={{ display: 'flex', gap: 8, marginTop: 12, alignItems: 'center', flexWrap: 'wrap' }}>
              <span style={{ color: 'var(--ink-3)', fontSize: 12 }}>Rang:</span>
              {PALETTE.map((c) => (
                <button
                  key={c}
                  type="button"
                  onClick={() => setColor(c)}
                  title={c}
                  style={{
                    width: 26, height: 26, borderRadius: 7, background: c, cursor: 'pointer',
                    border: color === c ? '3px solid var(--ink, #111)' : '2px solid transparent',
                    outline: color === c ? '1px solid var(--hair)' : 'none',
                  }}
                />
              ))}
            </div>
          </div>
        </Panel>
      )}

      <Panel className={isOwner ? 'gap-top' : ''}>
        <PanelHead
          sub="Ro‘yxat"
          title={`${groups.length} ta guruh · ${totalDevices} ta qurilma`}
        />
        <div style={{ overflowX: 'auto' }}>
          <table>
            <thead>
              <tr>
                <th>Guruh</th>
                <th>Kod</th>
                <th className="num">Qurilma</th>
                <th>Yaratilgan</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {loading && !groups.length ? (
                <tr><td colSpan={5}><Spinner label="Yuklanmoqda…" /></td></tr>
              ) : error && !groups.length ? (
                <tr><td colSpan={5}><Empty>Yuklab bo‘lmadi — qayta urinilmoqda…</Empty></td></tr>
              ) : !groups.length ? (
                <tr><td colSpan={5}><Empty>Hozircha guruh yo‘q{isOwner ? '. Yuqorida yangi guruh yarating.' : ''}</Empty></td></tr>
              ) : (
                groups.map((g) => (
                  <tr key={g.id}>
                    <td>
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
                        <span style={{ width: 12, height: 12, borderRadius: 3, background: g.color, display: 'inline-block', flex: '0 0 auto' }} />
                        <b>{g.name}</b>
                      </span>
                    </td>
                    <td className="mono"><b style={{ letterSpacing: 1 }}>{g.join_code}</b></td>
                    <td className="num click" onClick={() => setMem(g)} title="A‘zolarni ko‘rish" style={{ cursor: 'pointer', color: 'var(--primary)', fontWeight: 700 }}>{g.device_count || 0}</td>
                    <td style={{ color: 'var(--ink-3)', fontSize: 12 }}>{agoSafe(g.created_at)}</td>
                    <td>
                      <span style={{ display: 'inline-flex', gap: 6 }}>
                        <button className="btn sm" onClick={() => setMem(g)}>👥 A‘zolar</button>
                        <button className="btn sm" onClick={() => setQr(g)}>QR / kod</button>
                        {isOwner && <button className="btn sm danger" onClick={() => del(g)} disabled={busy}>O‘chirish</button>}
                      </span>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </Panel>

      {qr && <QrModal group={qr} onClose={() => setQr(null)} />}
      {mem && <MembersModal group={mem} onClose={() => setMem(null)} />}
    </>
  );
}
