import { useMemo, useState } from 'react';
import { usePoll } from '../hooks/usePoll';
import { apiGet, apiPost, type GroupRow } from '../lib/api';
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

// Modul matritsasidan to'liq SVG (quiet-zone 4 modul). Ko'rsatish va chop etish uchun bir xil.
function buildQrSvg(text: string, px = 240): string {
  const mods = qrModules(text, 'M');
  const n = mods.length;
  const quiet = 4;
  const dim = n + quiet * 2;
  let rects = '';
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      if (mods[y][x]) rects += `<rect x="${x + quiet}" y="${y + quiet}" width="1.02" height="1.02"/>`;
    }
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${px}" height="${px}" viewBox="0 0 ${dim} ${dim}" shape-rendering="crispEdges"><rect width="${dim}" height="${dim}" fill="#fff"/><g fill="#000">${rects}</g></svg>`;
}

function QrModal({ group, onClose }: { group: GroupRow; onClose: () => void }) {
  const url = joinUrl(group.join_code);
  const svg = useMemo(() => buildQrSvg(url, 260), [url]);

  // Chop etish — o'zimizning blank oyna (bir xil origin), faqat QR + kod + guruh nomi.
  const doPrint = () => {
    const w = window.open('', '_blank', 'width=420,height=560');
    if (!w) return;
    w.document.write(
      `<!doctype html><html><head><meta charset="utf-8"><title>${group.name}</title>` +
      `<style>body{font-family:system-ui,Arial,sans-serif;text-align:center;padding:32px;margin:0}` +
      `h1{font-size:20px;margin:0 0 4px}.code{font:700 34px/1.1 monospace;letter-spacing:4px;margin:16px 0}` +
      `.hint{color:#555;font-size:13px;margin-top:8px}svg{width:300px;height:300px}</style></head><body>` +
      `<h1>${group.name}</h1><div class="hint">UzGuard → «Guruhga qo‘shilish» → skanerlang</div>` +
      `${buildQrSvg(url, 300)}<div class="code">${group.join_code}</div>` +
      `<div class="hint">yoki shu kodni kiriting</div></body></html>`,
    );
    w.document.close();
    w.focus();
    setTimeout(() => { try { w.print(); } catch { /* foydalanuvchi qo'lda chop etadi */ } }, 250);
  };

  return (
    <>
      <div className="scrim" onClick={onClose} style={{ zIndex: 209 }} />
      <div
        style={{
          position: 'fixed', zIndex: 210, top: '50%', left: '50%', transform: 'translate(-50%,-50%)',
          background: 'var(--surface, #fff)', color: 'var(--ink, #111)', borderRadius: 16, padding: 24,
          width: 'min(360px, 92vw)', boxShadow: '0 24px 60px rgba(0,0,0,.35)', textAlign: 'center',
        }}
      >
        <button className="btn ghost" onClick={onClose} style={{ position: 'absolute', top: 10, right: 10 }}>✕</button>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8, marginBottom: 4 }}>
          <span style={{ width: 12, height: 12, borderRadius: 3, background: group.color, display: 'inline-block' }} />
          <b style={{ fontSize: 18 }}>{group.name}</b>
        </div>
        <div style={{ color: 'var(--ink-3)', fontSize: 12, marginBottom: 14 }}>
          Telefonda: «Guruhga qo‘shilish» → skanerlang yoki kodni kiriting
        </div>
        <div
          style={{ display: 'inline-block', background: '#fff', padding: 10, borderRadius: 12 }}
          dangerouslySetInnerHTML={{ __html: svg }}
        />
        <div style={{ font: '700 28px/1 monospace', letterSpacing: 4, margin: '16px 0 6px' }}>{group.join_code}</div>
        <button className="btn" onClick={doPrint} style={{ marginTop: 8 }}>🖨 Chop etish</button>
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
                    <td className="num">{g.device_count || 0}</td>
                    <td style={{ color: 'var(--ink-3)', fontSize: 12 }}>{agoSafe(g.created_at)}</td>
                    <td>
                      <span style={{ display: 'inline-flex', gap: 6 }}>
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
    </>
  );
}
