import { useEffect, useState } from 'react';
import { usePoll } from '../hooks/usePoll';
import { apiGet, type DeviceRow, type ScanRow } from '../lib/api';
import { Empty, Panel, PanelHead, Spinner, VerdictBadge } from '../components/ui';
import { agoSafe, catUz, riskColor, uzDateSafe } from '../lib/format';
import { nearestCity } from '../lib/uzRegions';

function DeviceDetail({ id, onClose }: { id: string; onClose: () => void }) {
  const [dev, setDev] = useState<DeviceRow | null>(null);
  const [scans, setScans] = useState<ScanRow[] | null>(null);
  const [err, setErr] = useState('');

  useEffect(() => {
    let alive = true;
    setDev(null); setScans(null); setErr('');
    apiGet<{ device: DeviceRow; scans: ScanRow[] }>(`/api/device/${id}`)
      .then((r) => { if (alive) { setDev(r.device); setScans(r.scans || []); } })
      .catch((e) => { if (alive) setErr((e as Error).message || 'xato'); });
    return () => { alive = false; };
  }, [id]);

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
              <div><span>Joylashuv</span><b>{((nearestCity(dev.lat, dev.lng) || dev.city) || '—') + ', ' + (dev.country || 'UZ')}</b></div>
              <div><span>IP manzil</span><b className="mono">{dev.ip || '—'}</b></div>
              <div><span>Android</span><b>{dev.android_ver || '—'}</b></div>
              <div><span>Ilova versiyasi</span><b>{dev.app_ver || '—'}</b></div>
              <div><span>Xavf bali</span><b style={{ color: riskColor(dev.risk_score || 0) }}>{Math.round(dev.risk_score || 0)}</b></div>
              <div><span>Ro‘yxatdan o‘tgan</span><b>{uzDateSafe(dev.created_at)}</b></div>
              <div><span>Oxirgi faollik</span><b>{agoSafe(dev.last_seen)}</b></div>
            </div>
            <div className="pp-thr-head" style={{ borderTop: '1px solid var(--hair)' }}>So‘nggi skanlar</div>
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
  const { data, loading, error } = usePoll(() => apiGet<{ devices: DeviceRow[] }>('/api/devices'), 15000);
  const devices = data?.devices || [];
  const [sel, setSel] = useState<string | null>(null);

  return (
    <>
      <div className="page-intro">
        <h1>Himoyalangan qurilmalar</h1>
        <p>KiberQalqon o‘rnatilgan qurilmalar, ularning xavf darajasi va skan tarixi. Qatorni bosib tafsilotni oching.</p>
      </div>

      <div className={'grid' + (sel ? ' map-grid' : '')}>
        <Panel>
          <PanelHead sub="Ro‘yxat" title={`${devices.length} ta qurilma`} />
          <div style={{ overflowX: 'auto' }}>
            <table>
              <thead>
                <tr>
                  <th>Qurilma</th>
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
                  <tr><td colSpan={7}><Spinner label="Yuklanmoqda…" /></td></tr>
                ) : error && !devices.length ? (
                  <tr><td colSpan={7}><Empty>Yuklab bo‘lmadi: {error}</Empty></td></tr>
                ) : !devices.length ? (
                  <tr><td colSpan={7}><Empty>Hozircha qurilma yo‘q</Empty></td></tr>
                ) : (
                  devices.map((d) => {
                    const risk = Math.round(d.risk_score || 0);
                    return (
                      <tr key={d.id} className="click" onClick={() => setSel(d.id)}>
                        <td><b>{d.name || 'Qurilma'}</b></td>
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
    </>
  );
}
