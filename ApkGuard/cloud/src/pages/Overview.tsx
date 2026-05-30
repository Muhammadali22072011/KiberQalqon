import { Link } from 'react-router-dom';
import { usePoll } from '../hooks/usePoll';
import { apiGet, type FeedItem, type Stats, type ThreatFamily } from '../lib/api';
import { Empty, Kpi, LivePill, Panel, PanelHead, Spinner, VerdictBadge } from '../components/ui';
import NewsCarousel from '../components/NewsCarousel';
import { agoSafe, catUz, SEV_COLOR, VERDICT_DOT } from '../lib/format';

export default function Overview() {
  const stats = usePoll(() => apiGet<{ stats: Stats }>('/api/stats'), 15000);
  const feed = usePoll(() => apiGet<{ feed: FeedItem[] }>('/api/feed'), 8000);
  const threats = usePoll(() => apiGet<{ threats: ThreatFamily[] }>('/api/threats'), 30000);

  const s = stats.data?.stats || {};
  const items = (feed.data?.feed || []).slice(0, 8);
  const top = [...(threats.data?.threats || [])]
    .sort((a, b) => (b.seen_count || 0) - (a.seen_count || 0))
    .slice(0, 5);
  const maxSeen = Math.max(1, ...top.map((t) => t.seen_count || 0));

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

      <div className="grid cols-2 gap-top">
        <Panel>
          <PanelHead sub="Real vaqt" title="So‘nggi tahdidlar" right={<LivePill />} />
          <div className="feed-scroll">
            {feed.loading && !items.length ? (
              <Spinner label="Yuklanmoqda…" />
            ) : !items.length ? (
              <Empty>Hozircha tahdid yo‘q</Empty>
            ) : (
              items.map((f, i) => (
                <div className="fi" key={(f.apk_hash || '') + i}>
                  <span className="sev" style={{ color: VERDICT_DOT[f.verdict] || '#9aa7c2' }} />
                  <div className="fi-main">
                    <div className="fi-app">{f.app_label || f.package_name || 'Nomaʼlum ilova'}</div>
                    <div className="fi-meta">{(f.city || '—') + ' · ' + agoSafe(f.scanned_at)}</div>
                  </div>
                  <VerdictBadge verdict={f.verdict} />
                </div>
              ))
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
            {!top.length ? (
              <Empty />
            ) : (
              top.map((t) => {
                const color = SEV_COLOR[t.severity || 'low'] || '#25e0b0';
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
    </>
  );
}
