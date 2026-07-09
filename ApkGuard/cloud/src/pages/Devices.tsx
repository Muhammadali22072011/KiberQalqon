import { useEffect, useMemo, useState } from 'react';
import { usePoll } from '../hooks/usePoll';
import { apiGet, apiPost, type DeviceCommand, type DeviceRow, type ScanRow } from '../lib/api';
import { Empty, Panel, PanelHead, Spinner, VerdictBadge } from '../components/ui';
import { agoSafe, catUz, riskColor, uzDateSafe } from '../lib/format';
import { nearestCity } from '../lib/uzRegions';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../components/Toast';

const CMD_STATUS_UZ: Record<string, string> = { pending: 'navbatda', done: 'yetkazildi', failed: 'xato' };

const VERDICT_FILTERS: Array<{ k: string; label: string }> = [
  { k: '', label: 'Barchasi' },
  { k: 'danger', label: 'Xavfli' },
  { k: 'suspicious', label: 'Shubhali' },
  { k: 'safe', label: 'Xavfsiz' },
];

function DeviceDetail({ id, onClose }: { id: string; onClose: () => void }) {
  const { isOwner } = useAuth();
  const { show } = useToast();
  const [dev, setDev] = useState<DeviceRow | null>(null);
  const [scans, setScans] = useState<ScanRow[] | null>(null);
  const [cmds, setCmds] = useState<DeviceCommand[]>([]);
  const [err, setErr] = useState('');
  const [sending, setSending] = useState(false);
  const [nonce, setNonce] = useState(0); // qayta yuklash uchun

  useEffect(() => {
    let alive = true;
    setDev(null); setScans(null); setErr('');
    apiGet<{ device: DeviceRow; scans: ScanRow[]; commands?: DeviceCommand[] }>(`/api/device/${id}`)
      .then((r) => { if (alive) { setDev(r.device); setScans(r.scans || []); setCmds(r.commands || []); } })
      .catch((e) => { if (alive) setErr((e as Error).message || 'xato'); });
    return () => { alive = false; };
  }, [id, nonce]);

  // #3: masofadan qayta skan buyrug'ini navbatga qo'yamiz. Qurilma keyingi ulanishida
  // (ilova ochilganda darhol, yoki fon "tirikman" signalida ~6 soatgacha) bajaradi.
  const sendRescan = async () => {
    if (sending) return;
    setSending(true);
    try {
      await apiPost(`/api/device/${id}`, { type: 'rescan' });
      show('Qayta skan navbatga qo‘yildi — qurilma keyingi ulanishida bajaradi');
      setNonce((n) => n + 1);
    } catch (e) {
      show(`Yuborib bo‘lmadi: ${(e as Error).message}`);
    } finally {
      setSending(false);
    }
  };

  return (
    <Panel>
      <PanelHead
        sub="Qurilma tafsiloti"
        title={dev?.name || 'Qurilma'}
        right={<button className="btn ghost" onClick={onClose}>✕</button>}
      />
      <div className="body-pad">
        {err ? (
          <Empty>{err}</Empty>
        ) : !dev || !scans ? (
          <Spinner label="Yuklanmoqda…" />
        ) : (
          <>
            <div className="pp-rows" style={{ borderTop: 'none' }}>
              {dev.group_id && (
                <div><span>Guruh</span><b style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                  <span style={{ width: 10, height: 10, borderRadius: 3, background: dev.group_color || '#888', display: 'inline-block' }} />
                  {dev.group_name || '—'}
                </b></div>
              )}
              {(dev.member_first || dev.member_last) && (
                <div><span>A‘zo</span><b>{[dev.member_first, dev.member_last].filter(Boolean).join(' ')}</b></div>
              )}
              {dev.member_phone && (
                <div><span>Telefon</span><b className="mono">{dev.member_phone}</b></div>
              )}
              <div><span>Joylashuv</span><b>{((nearestCity(dev.lat, dev.lng) || dev.city) || '—') + ', ' + (dev.country || 'UZ')}</b></div>
              <div><span>IP manzil</span><b className="mono">{dev.ip || '—'}</b></div>
              <div><span>Android</span><b>{dev.android_ver || '—'}</b></div>
              <div><span>Ilova versiyasi</span><b>{dev.app_ver || '—'}</b></div>
              <div><span>Xavf bali</span><b style={{ color: riskColor(dev.risk_score || 0) }}>{Math.round(dev.risk_score || 0)}</b></div>
              <div><span>Ro‘yxatdan o‘tgan</span><b>{uzDateSafe(dev.created_at)}</b></div>
              <div><span>Oxirgi faollik</span><b>{agoSafe(dev.last_seen)}</b></div>
            </div>
            {isOwner && (
              <>
                <div className="pp-thr-head" style={{ borderTop: '1px solid var(--hair)' }}>Masofaviy boshqaruv</div>
                <button className="btn sm" onClick={sendRescan} disabled={sending} style={{ margin: '4px 0 8px' }}>
                  {sending ? <span className="spinner" /> : '🔄 Masofadan qayta skan'}
                </button>
                {cmds.length > 0 && (
                  <div className="dd-cmds">
                    {cmds.map((c) => (
                      <div className="dd-cmd" key={c.id}>
                        <span>{c.type === 'rescan' ? 'Qayta skan' : c.type}</span>
                        <span className={'cmd-st cmd-' + c.status}>{CMD_STATUS_UZ[c.status] || c.status}</span>
                        <span className="mono" style={{ color: 'var(--ink-3)' }}>{agoSafe(c.created_at)}</span>
                      </div>
                    ))}
                  </div>
                )}
              </>
            )}
            {scans.length > 0 && (
              <div className="dd-verdicts">
                <span className="ddv" style={{ color: '#E0432F' }}><b>{scans.filter((s) => s.verdict === 'danger').length}</b> xavfli</span>
                <span className="ddv" style={{ color: '#DF8A18' }}><b>{scans.filter((s) => s.verdict === 'suspicious').length}</b> shubhali</span>
                <span className="ddv" style={{ color: '#1A9E54' }}><b>{scans.filter((s) => s.verdict === 'safe').length}</b> xavfsiz</span>
              </div>
            )}
            <div className="pp-thr-head" style={{ borderTop: '1px solid var(--hair)' }}>So‘nggi skanlar (oxirgi {scans.length})</div>
            {!scans.length ? (
              <Empty>Skan tarixi yo‘q</Empty>
            ) : (
              scans.map((s) => (
                <div className="fi" key={s.id}>
                  <div className="fi-main">
                    <div className="fi-app">{s.app_label || s.package_name || '—'}</div>
                    <div className="fi-meta">{(s.package_name || '') + ' · xavf ' + Math.round(s.risk_score || 0) + ' · ' + agoSafe(s.scanned_at)}</div>
                  </div>
                  <VerdictBadge verdict={s.verdict} />
                </div>
              ))
            )}
          </>
        )}
      </div>
    </Panel>
  );
}

export default function Devices() {
  const { data, loading, error } = usePoll(() => apiGet<{ devices: DeviceRow[]; total?: number }>('/api/devices'), 15000);
  const devices = data?.devices || [];
  const total = data?.total ?? devices.length;
  const [sel, setSel] = useState<string | null>(null);
  const [q, setQ] = useState('');
  const [vf, setVf] = useState('');
  const [gf, setGf] = useState(''); // '' = barchasi, 'none' = guruhsiz, aks holda group_id

  // Guruhlar ro'yxatini qurilmalardan yig'amiz (alohida so'rovsiz) — filtr uchun.
  const groupOpts = useMemo(() => {
    const m = new Map<string, { name: string; color: string }>();
    for (const d of devices) {
      if (d.group_id && !m.has(d.group_id)) m.set(d.group_id, { name: d.group_name || 'Guruh', color: d.group_color || '#888' });
    }
    return [...m.entries()].map(([id, v]) => ({ id, ...v }));
  }, [devices]);

  // Qidiruv (nom/shahar/IP/a'zo) + holat + guruh filtri — mijoz tomonida (ro'yxat kichik).
  const shown = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return devices.filter((d) => {
      if (vf && d.last_verdict !== vf) return false;
      if (gf === 'none' && d.group_id) return false;
      if (gf && gf !== 'none' && d.group_id !== gf) return false;
      if (!needle) return true;
      const hay = [d.name, nearestCity(d.lat, d.lng), d.city, d.ip,
        d.member_first, d.member_last, d.member_phone, d.group_name].filter(Boolean).join(' ').toLowerCase();
      return hay.includes(needle);
    });
  }, [devices, q, vf, gf]);

  // Ilova versiyalari taqsimoti (park bo'ylab) — raskatka nazorati uchun (eng ko'p 6 ta).
  const versions = useMemo(() => {
    const m = new Map<string, number>();
    for (const d of devices) {
      const v = (d.app_ver || '').trim() || '—';
      m.set(v, (m.get(v) || 0) + 1);
    }
    return [...m.entries()].sort((a, b) => b[1] - a[1]).slice(0, 6);
  }, [devices]);
  const verMax = Math.max(1, ...versions.map(([, n]) => n));

  return (
    <>
      <div className="page-intro">
        <h1>Himoyalangan qurilmalar</h1>
        <p>UzGuard o‘rnatilgan qurilmalar, ularning xavf darajasi va skan tarixi. Qatorni bosib tafsilotni oching.</p>
      </div>

      <div className={'grid' + (sel ? ' map-grid' : '')}>
        <Panel>
          <PanelHead sub="Ro‘yxat" title={total > devices.length ? `${devices.length} / ${total} ta qurilma` : `${total} ta qurilma`} />
          <div className="body-pad" style={{ paddingBottom: 0 }}>
            <div className="list-filter">
              <input
                className="search"
                placeholder="Qidirish: nom, shahar yoki IP…"
                value={q}
                onChange={(e) => setQ(e.target.value)}
              />
              <select value={vf} onChange={(e) => setVf(e.target.value)} style={{ width: 130 }}>
                {VERDICT_FILTERS.map((f) => <option key={f.k} value={f.k}>{f.label}</option>)}
              </select>
              {groupOpts.length > 0 && (
                <select value={gf} onChange={(e) => setGf(e.target.value)} style={{ width: 150 }}>
                  <option value="">Barcha guruh</option>
                  {groupOpts.map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
                  <option value="none">Guruhsiz</option>
                </select>
              )}
              {(q || vf || gf) && <span className="lf-count">{shown.length} / {devices.length}</span>}
            </div>
          </div>
          <div style={{ overflowX: 'auto' }}>
            <table>
              <thead>
                <tr>
                  <th>Qurilma</th>
                  <th>Guruh</th>
                  <th>Joy</th>
                  <th className="num">Skan</th>
                  <th className="num">Xavfli</th>
                  <th>Xavf</th>
                  <th>Holat</th>
                  <th>Faollik</th>
                </tr>
              </thead>
              <tbody>
                {loading && !devices.length ? (
                  <tr><td colSpan={8}><Spinner label="Yuklanmoqda…" /></td></tr>
                ) : error && !devices.length ? (
                  <tr><td colSpan={8}><Empty>Ma‘lumotni yuklab bo‘lmadi — qayta urinilmoqda…</Empty></td></tr>
                ) : !devices.length ? (
                  <tr><td colSpan={8}><Empty>Hozircha qurilma yo‘q</Empty></td></tr>
                ) : !shown.length ? (
                  <tr><td colSpan={8}><Empty>Filtrga mos qurilma topilmadi</Empty></td></tr>
                ) : (
                  shown.map((d) => {
                    const risk = Math.round(d.risk_score || 0);
                    return (
                      <tr key={d.id} className="click" onClick={() => setSel(d.id)}>
                        <td>
                          {(d.member_first || d.member_last)
                            ? <><b>{[d.member_first, d.member_last].filter(Boolean).join(' ')}</b><br /><span style={{ color: 'var(--ink-3)', fontSize: 12 }}>{d.name || 'Qurilma'}</span></>
                            : <b>{d.name || 'Qurilma'}</b>}
                        </td>
                        <td>
                          {d.group_id
                            ? <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                                <span style={{ width: 10, height: 10, borderRadius: 3, background: d.group_color || '#888', display: 'inline-block', flex: '0 0 auto' }} />
                                <span style={{ fontSize: 13 }}>{d.group_name || '—'}</span>
                              </span>
                            : <span style={{ color: 'var(--ink-3)' }}>—</span>}
                        </td>
                        <td style={{ color: 'var(--ink-2)' }}>{nearestCity(d.lat, d.lng) || d.city || '—'}</td>
                        <td className="num">{d.scan_count || 0}</td>
                        <td className={'num' + ((d.danger_count || 0) > 0 ? ' dgr' : '')}>{d.danger_count || 0}</td>
                        <td>
                          <span className="mini"><i style={{ width: `${risk}%`, background: riskColor(risk) }} /></span>
                          <span className="mono" style={{ marginLeft: 8, fontSize: 12, color: 'var(--ink-2)' }}>{risk}</span>
                        </td>
                        <td>{d.last_verdict ? <VerdictBadge verdict={d.last_verdict} /> : <span style={{ color: 'var(--ink-3)' }}>—</span>}</td>
                        <td style={{ color: 'var(--ink-3)', fontSize: 12 }}>{agoSafe(d.last_seen)}</td>
                      </tr>
                    );
                  })
                )}
              </tbody>
            </table>
          </div>
        </Panel>

        {sel && <DeviceDetail id={sel} onClose={() => setSel(null)} />}
      </div>

      {versions.length > 0 && (
        <Panel className="gap-top">
          <PanelHead sub="Raskatka" title="Ilova versiyalari (parkda)" />
          <div className="body-pad">
            {versions.map(([v, n]) => (
              <div className="ver-row" key={v}>
                <span className="vr-name">{v}</span>
                <span className="track">
                  <i className="fill" style={{ width: `${Math.round((100 * n) / verMax)}%`, background: 'var(--primary)' }} />
                </span>
                <span className="vr-cnt">{n}</span>
              </div>
            ))}
          </div>
        </Panel>
      )}
    </>
  );
}
