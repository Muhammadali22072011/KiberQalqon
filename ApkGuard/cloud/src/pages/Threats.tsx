import { useState } from 'react';
import { usePoll } from '../hooks/usePoll';
import { apiGet, apiPost, type ThreatDomain, type ThreatFamily } from '../lib/api';
import { Empty, Panel, PanelHead, Spinner, Tag } from '../components/ui';
import { agoSafe, catUz, SEV_COLOR, uzDateSafe } from '../lib/format';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../components/Toast';

const SEV_UZ: Record<string, string> = {
  critical: 'Kritik', high: 'Yuqori', medium: "O'rta", low: 'Past',
};

/**
 * Domen qora ro'yxati boshqaruvi. Bu yerga qo'shilgan domen IMZOLANGAN feed orqali
 * BARCHA telefonlarga tushadi: LinkScanner havolani DANGER deydi, VPN C2-filtri esa
 * DNS darajasida bloklaydi. Qo'shish/o'chirish FAQAT EGADA (server ham 403 bilan qaytaradi).
 */
function DomainsPanel() {
  const { isOwner } = useAuth();
  const { show } = useToast();
  const { data, loading, reload } = usePoll(
    () => apiGet<{ domains: ThreatDomain[] }>('/api/threats?domains=1'),
    60000,
  );
  const [domain, setDomain] = useState('');
  const [severity, setSeverity] = useState('high');
  const [busy, setBusy] = useState(false);
  const rows = data?.domains || [];

  const add = async () => {
    const d = domain.trim();
    if (!d || busy) return;
    setBusy(true);
    try {
      await apiPost('/api/threats', { action: 'add_domain', domain: d, severity });
      setDomain('');
      show(`${d} bloklandi — barcha telefonlarga tarqaladi`);
      reload();
    } catch (e) {
      show(`Qo‘shib bo‘lmadi: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  const del = async (d: string) => {
    if (busy) return;
    setBusy(true);
    try {
      await apiPost('/api/threats', { action: 'delete_domain', domain: d });
      show(`${d} ro‘yxatdan olib tashlandi`);
      reload();
    } catch (e) {
      show(`O‘chirib bo‘lmadi: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel className="gap-top">
      <PanelHead
        sub="Havola himoyasi · barcha telefonlarga tarqaladi"
        title={`Domen qora ro‘yxati (${rows.length})`}
      />
      <div className="body-pad">
        {isOwner && (
          <div className="row-inline" style={{ marginBottom: 12, flexWrap: 'wrap' }}>
            <input
              placeholder="masalan: payme-bonus.top"
              value={domain}
              onChange={(e) => setDomain(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') add(); }}
              style={{ flex: '1 1 220px' }}
            />
            <select value={severity} onChange={(e) => setSeverity(e.target.value)} style={{ width: 130 }}>
              <option value="critical">Kritik</option>
              <option value="high">Yuqori</option>
              <option value="medium">O‘rta</option>
            </select>
            <button className="btn" onClick={add} disabled={busy || !domain.trim()}>
              {busy ? <span className="spinner" /> : '+ Bloklash'}
            </button>
          </div>
        )}
        {loading && !rows.length ? (
          <Spinner label="Yuklanmoqda…" />
        ) : !rows.length ? (
          <Empty>Domen qo‘shilmagan. Yuqoriga firibgar saytni yozing — barcha telefonlar bloklaydi.</Empty>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table>
              <thead>
                <tr>
                  <th>Domen</th>
                  <th>Toifa</th>
                  <th>Daraja</th>
                  <th>Manba</th>
                  <th>Qo‘shilgan</th>
                  {isOwner && <th />}
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.domain}>
                    <td className="mono" style={{ fontSize: 13 }}><b>{r.domain}</b></td>
                    <td><Tag kind="comp">{catUz(r.category)}</Tag></td>
                    <td>
                      <span style={{ color: SEV_COLOR[r.severity || 'high'] || 'var(--warn)', fontWeight: 700, fontSize: 12 }}>
                        {SEV_UZ[r.severity || 'high'] || r.severity}
                      </span>
                    </td>
                    <td style={{ color: 'var(--ink-3)', fontSize: 12 }}>{r.source === 'owner' ? 'Egasi' : r.source || '—'}</td>
                    <td style={{ color: 'var(--ink-2)', fontSize: 12 }}>{agoSafe(r.last_seen)}</td>
                    {isOwner && (
                      <td>
                        <button className="btn sm danger" onClick={() => del(r.domain)} disabled={busy}>
                          O‘chirish
                        </button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </Panel>
  );
}

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
                <th>Namuna</th>
              </tr>
            </thead>
            <tbody>
              {error && !list.length ? (
                <tr><td colSpan={8}><Empty>Yuklab bo‘lmadi: {error}</Empty></td></tr>
              ) : !sorted.length ? (
                <tr><td colSpan={8}><Empty /></td></tr>
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
                    <td>
                      {t.sample_url && /^https:\/\//.test(t.sample_url) ? (
                        <a
                          href={t.sample_url}
                          download
                          rel="noopener noreferrer"
                          title="APK namunasini yuklab olish (o‘rganish uchun)"
                          style={{ color: 'var(--primary)', fontWeight: 700, fontSize: 12, whiteSpace: 'nowrap' }}
                        >
                          ⬇ APK
                        </a>
                      ) : (
                        <span style={{ color: 'var(--ink-3)', fontSize: 12 }}>—</span>
                      )}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </Panel>

      <DomainsPanel />
    </>
  );
}
