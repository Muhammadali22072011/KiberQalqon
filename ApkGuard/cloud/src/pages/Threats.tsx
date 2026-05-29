import { usePoll } from '../hooks/usePoll';
import { apiGet, type ThreatFamily } from '../lib/api';
import { Empty, Panel, PanelHead, Spinner, Tag } from '../components/ui';
import { agoSafe, catUz, SEV_COLOR, uzDateSafe } from '../lib/format';

const SEV_UZ: Record<string, string> = {
  critical: 'Kritik', high: 'Yuqori', medium: "O'rta", low: 'Past',
};

export default function Threats() {
  const { data, loading, error } = usePoll(() => apiGet<{ threats: ThreatFamily[] }>('/api/threats'), 20000);
  const list = data?.threats || [];
  const sorted = [...list].sort((a, b) => (b.seen_count || 0) - (a.seen_count || 0));
  const top = sorted.slice(0, 8);
  const max = Math.max(1, ...top.map((t) => t.seen_count || 0));

  return (
    <>
      <div className="page-intro">
        <h1>Eng faol tahdidlar</h1>
        <p>Aniqlangan zararli ilovalar oilalari — necha marta uchragani, toifasi va xavf darajasi bo‘yicha.</p>
      </div>

      <div className="grid map-grid">
        <Panel>
          <PanelHead sub="Tahlil" title="Eng ko‘p uchragan" />
          <div className="body-pad">
            {loading && !list.length ? (
              <Spinner label="Yuklanmoqda…" />
            ) : !top.length ? (
              <Empty>Hozircha tahdid qayd etilmagan</Empty>
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
                      <div className="fill" style={{ width: `${Math.round((100 * (t.seen_count || 0)) / max)}%`, background: color }} />
                    </div>
                    <div className="bar-cnt">{t.seen_count || 0}</div>
                  </div>
                );
              })
            )}
          </div>
        </Panel>

        <Panel>
          <PanelHead sub="Taqsimot" title="Toifalar bo‘yicha" />
          <div className="body-pad">
            {!list.length ? (
              <Empty />
            ) : (
              Object.entries(
                list.reduce<Record<string, number>>((acc, t) => {
                  const c = t.category || 'suspicious';
                  acc[c] = (acc[c] || 0) + (t.seen_count || 1);
                  return acc;
                }, {}),
              )
                .sort((a, b) => b[1] - a[1])
                .map(([cat, n]) => (
                  <div className="bar-row" key={cat} style={{ gridTemplateColumns: '1fr 56px' }}>
                    <div className="bar-name">
                      <span>{catUz(cat)}</span>
                    </div>
                    <div className="bar-cnt">{n}</div>
                  </div>
                ))
            )}
          </div>
        </Panel>
      </div>

      <Panel className="gap-top">
        <PanelHead sub="To‘liq ro‘yxat" title={`${list.length} ta tahdid oilasi`} />
        <div style={{ overflowX: 'auto' }}>
          <table>
            <thead>
              <tr>
                <th>Ilova</th>
                <th>Paket</th>
                <th>Toifa</th>
                <th>Daraja</th>
                <th className="num">Marta</th>
                <th>Birinchi</th>
                <th>Oxirgi</th>
              </tr>
            </thead>
            <tbody>
              {error && !list.length ? (
                <tr><td colSpan={7}><Empty>Yuklab bo‘lmadi: {error}</Empty></td></tr>
              ) : !sorted.length ? (
                <tr><td colSpan={7}><Empty /></td></tr>
              ) : (
                sorted.map((t) => (
                  <tr key={t.apk_hash}>
                    <td><b>{t.app_label || '—'}</b></td>
                    <td className="mono" style={{ color: 'var(--ink-3)', fontSize: 12 }}>{t.package_name || '—'}</td>
                    <td><Tag kind="comp">{catUz(t.category)}</Tag></td>
                    <td>
                      <span style={{ color: SEV_COLOR[t.severity || 'low'] || '#25e0b0', fontWeight: 700, fontSize: 12 }}>
                        {SEV_UZ[t.severity || 'low'] || t.severity || '—'}
                      </span>
                    </td>
                    <td className="num">{t.seen_count || 0}</td>
                    <td style={{ color: 'var(--ink-3)', fontSize: 12 }}>{uzDateSafe(t.first_seen)}</td>
                    <td style={{ color: 'var(--ink-2)', fontSize: 12 }}>{agoSafe(t.last_seen)}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </Panel>
    </>
  );
}
