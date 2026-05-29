import { useEffect, useRef, useState } from 'react';
import { CircleMarker, MapContainer, Popup, TileLayer, Tooltip, useMap } from 'react-leaflet';
import type { LatLngBoundsExpression, LatLngExpression } from 'leaflet';
import { usePoll } from '../hooks/usePoll';
import { apiGet, type DeviceRow, type MapPoint, type ScanRow } from '../lib/api';
import { LivePill, Panel, PanelHead, Spinner } from '../components/ui';
import {
  agoSafe, riskColor, scoreVerdict, VERDICT_BG, VERDICT_FG, VERDICT_UZ,
} from '../lib/format';

// O'zbekiston markazi va taxminiy chegaralari.
const UZ_CENTER: LatLngExpression = [41.3, 64.6];
const UZ_BOUNDS: LatLngBoundsExpression = [
  [37.1, 55.9],
  [45.6, 73.2],
];

function verdictOf(p: MapPoint): string {
  if (p.last_verdict && VERDICT_UZ[p.last_verdict]) return p.last_verdict;
  return scoreVerdict(p.risk_score || 0);
}

// Birinchi marta nuqtalar kelganda xaritani ularga moslaymiz.
function FitBounds({ points }: { points: MapPoint[] }) {
  const map = useMap();
  const done = useRef(false);
  useEffect(() => {
    if (done.current) return;
    const pts = points
      .filter((p) => p.lat != null && p.lng != null)
      .map((p) => [p.lat as number, p.lng as number] as [number, number]);
    if (pts.length >= 1) {
      map.fitBounds(pts.length === 1 ? [pts[0], pts[0]] : pts, { padding: [50, 50], maxZoom: 11 });
      done.current = true;
    }
  }, [points, map]);
  return null;
}

// Popup ochilganda qurilmaning so'nggi tahdidlarini yuklaymiz.
function DeviceThreats({ id }: { id: string }) {
  const [scans, setScans] = useState<ScanRow[] | null>(null);
  const [err, setErr] = useState(false);
  useEffect(() => {
    let alive = true;
    apiGet<{ device: DeviceRow; scans: ScanRow[] }>(`/api/device/${id}`)
      .then((r) => { if (alive) setScans(r.scans || []); })
      .catch(() => { if (alive) setErr(true); });
    return () => { alive = false; };
  }, [id]);

  if (err) return <div className="pp-load">Maʼlumot yuklanmadi</div>;
  if (!scans) return <div className="pp-load">Yuklanmoqda…</div>;
  const threats = scans.filter((s) => s.verdict === 'danger' || s.verdict === 'suspicious').slice(0, 4);
  if (!threats.length) return <div className="pp-clean">✓ Tahdid topilmadi</div>;
  return (
    <>
      <div className="pp-thr-head">Topilgan tahdidlar</div>
      {threats.map((s) => (
        <div className="pp-thr" key={s.id}>
          <i style={{ background: s.verdict === 'danger' ? '#ff3b5c' : '#ffb020' }} />
          <div>
            <b>{s.app_label || s.package_name || '—'}</b>
            <span>{(s.package_name || '') + ' · xavf ' + Math.round(s.risk_score || 0) + ' · ' + agoSafe(s.scanned_at)}</span>
          </div>
        </div>
      ))}
    </>
  );
}

export default function MapPage() {
  const { data, loading, error } = usePoll(() => apiGet<{ points: MapPoint[] }>('/api/geo'), 12000);
  const points = (data?.points || []).filter((p) => p.lat != null && p.lng != null);

  return (
    <>
      <div className="page-intro">
        <h1>O‘zbekiston himoya xaritasi</h1>
        <p>Himoyalangan qurilmalar va aniqlangan tahdidlar real vaqtda xaritada. Nuqta rangi — xavf darajasi (yashil → qizil). Markerni bosing.</p>
      </div>

      <Panel>
        <PanelHead
          sub="Geo monitoring"
          title={`${points.length} ta qurilma kuzatuvda`}
          right={
            <div className="row-inline">
              <span className="legend">
                Past <span className="grad-bar" /> Yuqori
              </span>
              <LivePill />
            </div>
          }
        />
        <div className="map-box tall">
          {loading && !points.length ? (
            <Spinner label="Xarita yuklanmoqda…" />
          ) : (
            <MapContainer
              center={UZ_CENTER}
              zoom={6}
              minZoom={4}
              maxBounds={UZ_BOUNDS}
              maxBoundsViscosity={0.7}
              scrollWheelZoom
              style={{ height: '100%', width: '100%' }}
            >
              <TileLayer
                url="https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png"
                attribution='&copy; OpenStreetMap · &copy; CARTO'
                subdomains="abcd"
                maxZoom={20}
              />
              <FitBounds points={points} />
              {points.map((p) => {
                const v = verdictOf(p);
                const color = riskColor(p.risk_score || 0);
                const r = 7 + Math.min(13, ((p.risk_score || 0) / 100) * 13);
                return (
                  <CircleMarker
                    key={p.id}
                    center={[p.lat as number, p.lng as number]}
                    radius={r}
                    pathOptions={{ color, fillColor: color, fillOpacity: 0.55, weight: 2 }}
                  >
                    <Tooltip direction="top" offset={[0, -4]}>
                      {(p.name || 'Qurilma') + ' · ' + (p.city || '—')}
                    </Tooltip>
                    <Popup className="kq-pop">
                      <div className="pp">
                        <div className="pp-bar" style={{ background: color }} />
                        <div className="pp-head">
                          <div className="pp-model">{p.name || 'Qurilma'}</div>
                          <div className="pp-sub">{(p.city || '—') + ', ' + (p.country || 'UZ')}</div>
                        </div>
                        <div className="pp-score">
                          <span
                            className="pp-badge"
                            style={{ background: VERDICT_BG[v], color: VERDICT_FG[v] }}
                          >
                            {VERDICT_UZ[v]}
                          </span>
                          <span className="pp-num">Xavf: <b>{Math.round(p.risk_score || 0)}</b></span>
                        </div>
                        <div className="pp-rows">
                          <div><span>Skanlar</span><b>{p.scan_count || 0}</b></div>
                          <div><span>Xavfli</span><b style={{ color: '#ff6679' }}>{p.danger_count || 0}</b></div>
                          <div><span>Oxirgi faollik</span><b>{agoSafe(p.last_seen || p.last_scan_at)}</b></div>
                        </div>
                        <DeviceThreats id={p.id} />
                      </div>
                    </Popup>
                  </CircleMarker>
                );
              })}
            </MapContainer>
          )}
        </div>
      </Panel>

      {error && (
        <div className="note warn" style={{ marginTop: 16 }}>
          <span className="ni">⚠️</span>
          <span>Xarita maʼlumotini yuklashda xatolik: {error}</span>
        </div>
      )}
    </>
  );
}
