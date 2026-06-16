import { Link } from 'react-router-dom';
import { useEffect, useMemo, useRef, useState } from 'react';
import { usePoll } from '../hooks/usePoll';
import { apiGet, type FeedItem, type ScanPerf, type Stats, type ThreatFamily } from '../lib/api';
import { Empty, Kpi, LivePill, Panel, PanelHead, ScannerAnatomy, Spinner, VerdictBadge } from '../components/ui';
import NewsCarousel from '../components/NewsCarousel';
import { agoSafe, catUz, SEV_COLOR, VERDICT_DOT } from '../lib/format';

// Raqobatchi modeli: APK'ni VirusTotal'ga YUKLAB tekshirish (Apk Qo'riqchi shunday ishlaydi).
// Bu o'ylab topilgan son EMAS — har bir APK ning real hajmidan (apk_size) hisoblanadi:
//   vaqt ≈ navbat/tahlil + (fayl hajmi ÷ yuklash tezligi). "taxminiy" deb belgilangan model.
const VT_UPLOAD_KBPS = 500;   // taxminiy yuklash tezligi (KB/s)
const VT_QUEUE_MS = 8000;     // VirusTotal navbat + tahlil (taxminiy, ms)

export default function Overview() {
  const stats = usePoll(() => apiGet<{ stats: Stats }>('/api/stats'), 15000);
  const feed = usePoll(() => apiGet<{ feed: FeedItem[] }>('/api/feed'), 8000);
  const threats = usePoll(() => apiGet<{ threats: ThreatFamily[] }>('/api/threats'), 30000);
  const perf = usePoll(() => apiGet<{ perf: ScanPerf }>('/api/stats?perf=1'), 15000);

  const [selectedScan, setSelectedScan] = useState<FeedItem | null>(null);

  const s = stats.data?.stats || {};
  const p = perf.data?.perf;
  const items = useMemo(() => (feed.data?.feed || []).slice(0, 8), [feed.data]);
  const top = [...(threats.data?.threats || [])]
    .sort((a, b) => (b.seen_count || 0) - (a.seen_count || 0))
    .slice(0, 5);
  const maxSeen = Math.max(1, ...top.map((t) => t.seen_count || 0));

  // ── Jonli himoya pulsi: yangi kelgan yozuvlarni "fresh" deb belgilaymiz (qisqa porlash) ──
  const ekgKey = (f: FeedItem) => (f.apk_hash || '') + '|' + f.scanned_at;
  const seen = useRef<Set<string>>(new Set());
  const inited = useRef(false);
  const [fresh, setFresh] = useState<Set<string>>(new Set());
  useEffect(() => {
    if (!items.length) return;
    const keys = items.map(ekgKey);
    if (!inited.current) { keys.forEach((k) => seen.current.add(k)); inited.current = true; return; }
    const added = keys.filter((k) => !seen.current.has(k));
    if (!added.length) return;
    added.forEach((k) => seen.current.add(k));
    setFresh(new Set(added));
    const t = window.setTimeout(() => setFresh(new Set()), 1600);
    return () => window.clearTimeout(t);
  }, [items]);

  // Pulsni vaqt bo'yicha chapdan o'ngga chizamiz (eski → yangi). Y: xavfsiz tepada, xavfli pastda.
  const ekg = useMemo(() => [...items].reverse(), [items]);
  const ekgY = (v: string) => (v === 'safe' ? 14 : v === 'danger' ? 46 : 30);
  const ekgX = (i: number) => (ekg.length <= 1 ? 150 : (i / (ekg.length - 1)) * 300);
  const ekgPoints = ekg.length
    ? ekg.map((f, i) => `${ekgX(i).toFixed(1)},${ekgY(f.verdict)}`).join(' ')
    : '0,30 300,30';

  // ── Mamlakat himoya darajasi (0..100): xavfsizlar ulushidan xavfli/shubhalilar jarimasi ──
  // Maʼlumot yo'q (total=0) bo'lsa null — "100%" KO'RSATMAYMIZ: "maʼlumot yo'q" ni "hammasi
  // mukammal" deb ko'rsatish movement #1 invariantining buzilishi (xato → yashil tomonga).
  const health = useMemo<number | null>(() => {
    const total = s.total_scans || 0;
    if (!total) return null;
    const danger = s.danger_count || 0;
    const susp = s.suspicious_count || 0;
    const safe = s.safe_count || 0;
    return Math.max(0, Math.min(100, Math.round(((safe - danger * 2 - susp) / total) * 100)));
  }, [s.total_scans, s.danger_count, s.suspicious_count, s.safe_count]);
  const noHealthData = health == null;
  const h = health ?? 0;
  const healthColor = noHealthData ? '#9A8D82' : h > 70 ? '#1A9E54' : h > 40 ? '#DF8A18' : '#E0432F';
  const ARC = Math.PI * 50; // yarim doira yoyi uzunligi (r = 50)
  const knobAng = ((180 - 1.8 * h) * Math.PI) / 180;
  const knobX = 60 + 50 * Math.cos(knobAng);
  const knobY = 60 - 50 * Math.sin(knobAng);

  // ── Tekshiruv tezligi: bizning median (qurilmada, offline) vs raqobatchi modeli (VirusTotal) ──
  const ourMs = p?.median_ms || 0;
  const avgBytes = p?.slowest?.length
    ? p.slowest.reduce((a, x) => a + (x.apk_size || 0), 0) / p.slowest.length
    : 5 * 1024 * 1024;
  const competitorMs = Math.round(VT_QUEUE_MS + (avgBytes / (VT_UPLOAD_KBPS * 1024)) * 1000);
  const ourPct = competitorMs ? Math.max(1.5, Math.min(100, (ourMs / competitorMs) * 100)) : 1.5;
  const fmtMs = (ms: number) => (ms >= 1000 ? (ms / 1000).toFixed(1) + ' s' : Math.round(ms) + ' ms');

  return (
    <>
      <div className="page-intro">
        <h1>Bosh sahifa</h1>
        <p>Tizimning umumiy holati — bugungi skanlar, faol qurilmalar va so‘nggi tahdidlar bir joyda.</p>
      </div>

      <div className="kpis">
        <Kpi icon="🧪" label="Bugungi skanlar" value={s.total_scans || 0} hint="oxirgi 24 soat" accent="#4aa8ff" />
        <Kpi icon="⛔" label="Xavfli" value={s.danger_count || 0} hint="aniqlangan tahdid" accent="#ff4361" />
        <Kpi icon="⚠️" label="Shubhali" value={s.suspicious_count || 0} hint="tekshiruv kerak" accent="#ffb020" />
        <Kpi icon="✅" label="Xavfsiz" value={s.safe_count || 0} hint="toza ilovalar" accent="#25e0b0" />
        <Kpi icon="📱" label="Faol qurilmalar" value={s.active_devices || 0} hint="himoya ostida" accent="#8b7bff" />
      </div>

      {stats.error && (
        <div className="note warn" style={{ marginBottom: 16 }}>
          <span className="ni">⚠️</span>
          <span>Statistikani yuklashda xatolik: {stats.error}. Avtomatik qayta urinilmoqda…</span>
        </div>
      )}

      <NewsCarousel />

      {/* ── Jonli holat qatori: tezlik dueli · himoya pulsi · mamlakat darajasi ── */}
      <div className="grid cols-3 gap-top">
        <Panel>
          <PanelHead sub="Qurilmada, internetsiz" title="Tekshiruv tezligi" right={<LivePill />} />
          <div className="perf-hero">
            <b>{ourMs ? fmtMs(ourMs) : '—'}</b>
            <small>median{p?.p95_ms ? ` · p95 ${fmtMs(p.p95_ms)}` : ''}</small>
          </div>
          <div className="body-pad">
            <div className="duel-row">
              <div className="duel-name"><i style={{ background: '#1A9E54' }} /><span>UzGuard (offline)</span></div>
              <div className="track"><div className="fill" style={{ width: `${ourPct}%`, background: '#1A9E54' }} /></div>
              <div className="duel-cnt">{ourMs ? fmtMs(ourMs) : '—'}</div>
            </div>
            <div className="duel-row">
              <div className="duel-name"><i style={{ background: '#9A8D82' }} /><span>Raqobatchi (VirusTotal'ga yuklab)</span></div>
              <div className="track"><div className="fill" style={{ width: '100%', background: '#9A8D82' }} /></div>
              <div className="duel-cnt">~{fmtMs(competitorMs)}</div>
            </div>
          </div>
          <div className="perf-foot">
            Raqobatchi vaqti — <b>taxminiy</b> model: fayl hajmi (~{(Math.round((avgBytes / 1024 / 1024) * 10) / 10)} MB) ÷ yuklash tezligi + tahlil navbati.
            UzGuard faylni qurilmaning o‘zida, internetsiz tekshiradi.
          </div>
        </Panel>

        <Panel>
          <PanelHead sub="Real vaqt" title="Jonli himoya pulsi" right={<LivePill />} />
          <div className="ekg-box">
            <svg className="ekg" viewBox="0 0 300 60" preserveAspectRatio="none">
              <polyline className="ekg-line" points={ekgPoints} />
              {ekg.map((f, i) => {
                const isFresh = fresh.has(ekgKey(f));
                const c = VERDICT_DOT[f.verdict] || '#9A8D82';
                return (
                  <circle
                    key={i}
                    cx={ekgX(i)}
                    cy={ekgY(f.verdict)}
                    r={isFresh ? 5 : 2.4}
                    fill={c}
                    style={isFresh ? { filter: `drop-shadow(0 0 6px ${c})` } : undefined}
                  />
                );
              })}
            </svg>
          </div>
          <div className="ekg-legend">
            {!ekg.length ? (
              <span>Hozircha tekshiruv yo‘q</span>
            ) : (
              <>
                <span><i style={{ background: '#1A9E54' }} />Xavfsiz</span>
                <span><i style={{ background: '#DF8A18' }} />Shubhali</span>
                <span><i style={{ background: '#E0432F' }} />Xavfli</span>
              </>
            )}
          </div>
        </Panel>

        <Panel>
          <PanelHead sub="Umumiy holat" title="Mamlakat himoya darajasi" />
          <div className="gauge-box">
            <svg className="gauge" viewBox="0 0 120 72">
              <path d="M 10 60 A 50 50 0 0 1 110 60" fill="none" stroke="rgba(32,22,15,.08)" strokeWidth="9" strokeLinecap="round" />
              <path
                className="gauge-fill"
                d="M 10 60 A 50 50 0 0 1 110 60"
                fill="none"
                stroke={healthColor}
                strokeWidth="9"
                strokeLinecap="round"
                strokeDasharray={`${(h / 100) * ARC} ${ARC}`}
              />
              <circle cx={knobX} cy={knobY} r="5.5" fill="#fff" stroke={healthColor} strokeWidth="2" />
            </svg>
          </div>
          <div className="gauge-num" style={{ color: healthColor }}>{noHealthData ? '—' : `${health}%`}</div>
          <div className="gauge-sub">{noHealthData ? "Ma‘lumot yetarli emas" : 'Himoyalanganlik darajasi'}</div>
        </Panel>
      </div>

      <div className="grid cols-2 gap-top">
        <Panel>
          <PanelHead sub="Real vaqt" title="So‘nggi tahdidlar" right={<LivePill />} />
          <div className="feed-scroll">
            {feed.loading && !items.length ? (
              <Spinner label="Yuklanmoqda…" />
            ) : feed.error && !items.length ? (
              <Empty>Oqim uzildi — qayta urinilmoqda…</Empty>
            ) : !items.length ? (
              <Empty>Hozircha tahdid yo‘q</Empty>
            ) : (
              items.map((f, i) => {
                const clickable = f.verdict === 'danger' || f.verdict === 'suspicious';
                return (
                  <div
                    className="fi"
                    key={(f.apk_hash || '') + i}
                    onClick={clickable ? () => setSelectedScan(f) : undefined}
                    style={clickable ? { cursor: 'pointer' } : undefined}
                    title={clickable ? 'Tahlil tafsilotlari' : undefined}
                  >
                    <span className="sev" style={{ color: VERDICT_DOT[f.verdict] || '#9A8D82' }} />
                    <div className="fi-main">
                      <div className="fi-app">{f.app_label || f.package_name || 'Nomaʼlum ilova'}</div>
                      <div className="fi-meta">{(f.city || '—') + ' · ' + agoSafe(f.scanned_at)}</div>
                    </div>
                    <VerdictBadge verdict={f.verdict} />
                  </div>
                );
              })
            )}
          </div>
        </Panel>

        <Panel>
          <PanelHead
            sub="Tahlil"
            title="Eng faol tahdidlar"
            right={<Link className="btn ghost" to="/app/threats">Hammasi</Link>}
          />
          <div className="body-pad">
            {threats.error && !top.length ? (
              <Empty>Ma‘lumot yuklanmadi — qayta urinilmoqda…</Empty>
            ) : !top.length ? (
              <Empty />
            ) : (
              top.map((t) => {
                const color = SEV_COLOR[t.severity || 'low'] || '#1A9E54';
                return (
                  <div className="bar-row" key={t.apk_hash}>
                    <div className="bar-name">
                      <i style={{ background: color }} />
                      <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {t.app_label || t.package_name || '—'}
                        <em> · {catUz(t.category)}</em>
                      </span>
                    </div>
                    <div className="track">
                      <div
                        className="fill"
                        style={{ width: `${Math.round((100 * (t.seen_count || 0)) / maxSeen)}%`, background: color }}
                      />
                    </div>
                    <div className="bar-cnt">{t.seen_count || 0}</div>
                  </div>
                );
              })
            )}
          </div>
        </Panel>
      </div>

      <div className="grid cols-3 gap-top">
        <Link to="/app/map" className="lfeat" style={{ cursor: 'pointer' }}>
          <div className="lf-ico">🗺️</div>
          <h3>Geo xarita</h3>
          <p>Qurilmalar va tahdidlarni O‘zbekiston xaritasida real vaqtda kuzating.</p>
        </Link>
        <Link to="/app/devices" className="lfeat" style={{ cursor: 'pointer' }}>
          <div className="lf-ico">📱</div>
          <h3>Qurilmalar</h3>
          <p>Himoyalangan qurilmalar ro‘yxati, xavf darajasi va skan tarixi.</p>
        </Link>
        <Link to="/app/news" className="lfeat" style={{ cursor: 'pointer' }}>
          <div className="lf-ico">📰</div>
          <h3>E‘lonlar</h3>
          <p>Rahbariyat e‘lonlari va yangiliklar lentasi.</p>
        </Link>
      </div>

      {selectedScan && (
        <ScannerAnatomy
          reasons={selectedScan.reasons}
          riskScore={selectedScan.risk_score ?? undefined}
          verdict={selectedScan.verdict}
          appLabel={selectedScan.app_label || selectedScan.package_name || undefined}
          onClose={() => setSelectedScan(null)}
        />
      )}
    </>
  );
}
