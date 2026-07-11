import { useMemo, useState } from 'react';
import { usePoll } from '../hooks/usePoll';
import {
  apiGet, apiPost,
  type KnownGood, type RuleStat, type ThreatDomain, type ThreatFamily, type ThreatRule,
} from '../lib/api';
import { Empty, Panel, PanelHead, Spinner, Tag } from '../components/ui';
import { agoSafe, catUz, SEV_COLOR, uzDateSafe } from '../lib/format';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../components/Toast';

const SEV_UZ: Record<string, string> = {
  critical: 'Kritik', high: 'Yuqori', medium: "O'rta", low: 'Past',
};
const TARGET_UZ: Record<string, string> = {
  dex_string: 'DEX satri', manifest: 'Manifest', path: 'Fayl yo‘li',
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
  const [bulk, setBulk] = useState('');
  const [bulkBusy, setBulkBusy] = useState(false);
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

  // Ko'p domenni bir vaqtda import (IOC ro'yxati). Bo'sh joy/vergul/qator bo'yicha ajratamiz,
  // protokol/yo'lni olib tashlaymiz, dublikatni yig'amiz. Server har birini alohida validatsiya
  // qiladi (bank/gov allowlist, ommaviy-suffiks gate) — o'tmaganlarni sanaymiz.
  const importBulk = async () => {
    if (bulkBusy) return;
    const uniq = [...new Set(
      bulk.split(/[\s,;]+/)
        .map((s) => s.trim().toLowerCase().replace(/^https?:\/\//, '').replace(/\/.*$/, ''))
        .filter(Boolean),
    )];
    if (!uniq.length) { show('Domen topilmadi'); return; }
    setBulkBusy(true);
    let ok = 0; let fail = 0;
    for (const d of uniq) {
      try { await apiPost('/api/threats', { action: 'add_domain', domain: d, severity }); ok += 1; }
      catch { fail += 1; }
    }
    setBulk('');
    show(`${ok} ta bloklandi${fail ? `, ${fail} ta o‘tmadi (bank/gov yoki noto‘g‘ri)` : ''} — telefonlarga tarqaladi`);
    reload();
    setBulkBusy(false);
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
            {/* Faqat critical/high — qurilma feed'i (api/threats?feed=1) FAQAT shu ikkitasini
                tarqatadi. "O'rta" varianti telefonlarga yetib bormaydigan yozuv yaratardi,
                panel esa "bloklandi" deb aldab qo'yardi. */}
            <select value={severity} onChange={(e) => setSeverity(e.target.value)} style={{ width: 130 }}>
              <option value="critical">Kritik</option>
              <option value="high">Yuqori</option>
            </select>
            <button className="btn" onClick={add} disabled={busy || !domain.trim()}>
              {busy ? <span className="spinner" /> : '+ Bloklash'}
            </button>
          </div>
        )}
        {isOwner && (
          <details className="bulk-import">
            <summary>Ko‘p domen import (IOC ro‘yxati)</summary>
            <p className="bulk-hint">Har qatorga bitta domen (yoki vergul/probel bilan). Tanlangan daraja: <b>{severity === 'critical' ? 'Kritik' : 'Yuqori'}</b>. Bank/gov domenlari avtomatik rad etiladi.</p>
            <textarea
              className="bulk-ta"
              placeholder={'payme-bonus.top\nclick-pul.xyz\nuzcard-aksiya.online'}
              value={bulk}
              onChange={(e) => setBulk(e.target.value)}
              rows={5}
            />
            <button className="btn" onClick={importBulk} disabled={bulkBusy || !bulk.trim()} style={{ marginTop: 8 }}>
              {bulkBusy ? <span className="spinner" /> : '⬆ Hammasini bloklash'}
            </button>
          </details>
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

/**
 * #5 Namuna navbati (egasi): korroboratsiyalangan yuqori/kritik tahdidlarni ko'rib chiqish.
 * "Tasdiqlash" — feed'da qoladi; "Rad etish" — imzolangan feed'dan chiqariladi (noto'g'ri
 * topilgan bo'lsa butun parkni bloklab qo'ymaslik uchun). "Oila" — kampaniya yorlig'i (#6).
 */
function ReviewQueue({ items, onChanged }: { items: ThreatFamily[]; onChanged: () => void }) {
  const { show } = useToast();
  const [busy, setBusy] = useState<string | null>(null);
  const [fam, setFam] = useState<Record<string, string>>({});
  const pending = items.filter((t) =>
    (t.review_status == null || t.review_status === 'pending') &&
    ['high', 'critical'].includes(t.severity || ''));
  if (!pending.length) return null;

  const act = async (hash: string, body: Record<string, unknown>, okMsg: string) => {
    setBusy(hash);
    try { await apiPost('/api/threats', body); show(okMsg); onChanged(); }
    catch (e) { show(`Xato: ${(e as Error).message}`); }
    finally { setBusy(null); }
  };

  return (
    <Panel className="gap-top">
      <PanelHead sub="Ko‘rib chiqish · egasi" title={`Namuna navbati (${pending.length})`} />
      <div className="body-pad">
        <p className="bulk-hint">Yangi topilgan yuqori/kritik tahdidlar. <b>Tasdiqlash</b> — feed‘da qoladi; <b>Rad etish</b> — imzolangan feed‘dan chiqariladi.</p>
        {pending.map((t) => (
          <div className="rq-row" key={t.apk_hash}>
            <div className="rq-main">
              <b>{t.app_label || t.package_name || '—'}</b>
              <span className="mono">{(t.package_name || t.apk_hash.slice(0, 16)) + ' · ' + (t.seen_count || 0) + '×'}</span>
            </div>
            <input
              className="rq-fam"
              placeholder="Oila (Ajina.Banker…)"
              defaultValue={t.family || ''}
              onChange={(e) => setFam((m) => ({ ...m, [t.apk_hash]: e.target.value }))}
            />
            <div className="rq-btns">
              <button className="btn sm ghost" disabled={busy === t.apk_hash}
                onClick={() => act(t.apk_hash, { action: 'set_family', apk_hash: t.apk_hash, family: fam[t.apk_hash] ?? t.family ?? '' }, 'Oila belgilandi')}>Oila</button>
              <button className="btn sm" disabled={busy === t.apk_hash}
                onClick={() => act(t.apk_hash, { action: 'set_review', apk_hash: t.apk_hash, review_status: 'confirmed' }, 'Tasdiqlandi')}>Tasdiqlash</button>
              <button className="btn sm danger" disabled={busy === t.apk_hash}
                onClick={() => act(t.apk_hash, { action: 'set_review', apk_hash: t.apk_hash, review_status: 'dismissed' }, 'Rad etildi — feed‘dan chiqarildi')}>Rad etish</button>
            </div>
          </div>
        ))}
      </div>
    </Panel>
  );
}

/**
 * YARA-lite qoidalar boshqaruvi. Bu yerga qo'shilgan qoida IMZOLANGAN feed orqali barcha
 * telefonlarga tushadi (DEX satri / manifest / fayl yo'li bo'yicha kalitlar mos kelsa aniqlaydi).
 * Susaytirilgan (muted) qoida faqat maslahat sifatida qoladi. Qo'shish/o'chirish FAQAT EGADA.
 */
function RulesPanel() {
  const { isOwner } = useAuth();
  const { show } = useToast();
  const { data, loading, reload } = usePoll(
    () => apiGet<{ rules: ThreatRule[] }>('/api/threats?rules=1'),
    60000,
  );
  const rows = data?.rules || [];
  const [ruleId, setRuleId] = useState('');
  const [family, setFamily] = useState('');
  const [severity, setSeverity] = useState('high');
  const [target, setTarget] = useState('dex_string');
  const [needles, setNeedles] = useState('');
  const [minHits, setMinHits] = useState('0');
  const [busy, setBusy] = useState(false);

  const add = async () => {
    const rid = ruleId.trim();
    // Kalitlarni vergul yoki qator bo'yicha ajratamiz, dublikatni yig'amiz.
    const nlist = [...new Set(needles.split(/[\n,]+/).map((s) => s.trim()).filter(Boolean))];
    if (!rid || busy) return;
    if (!nlist.length) { show('Kamida bitta kalit (needle) kiriting'); return; }
    setBusy(true);
    try {
      await apiPost('/api/threats', {
        action: 'add_rule', rule_id: rid, family: family.trim(), severity, target,
        needles: nlist, min_hits: Number(minHits) || 0,
      });
      setRuleId(''); setFamily(''); setNeedles(''); setMinHits('0');
      show(`${rid} qoidasi qo‘shildi — telefonlarga tarqaladi`);
      reload();
    } catch (e) {
      show(`Qo‘shib bo‘lmadi: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  const del = async (rid: string) => {
    if (busy) return;
    setBusy(true);
    try {
      await apiPost('/api/threats', { action: 'delete_rule', rule_id: rid });
      show(`${rid} o‘chirildi`);
      reload();
    } catch (e) {
      show(`O‘chirib bo‘lmadi: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  const toggleMute = async (r: ThreatRule) => {
    if (busy) return;
    setBusy(true);
    try {
      await apiPost('/api/threats', { action: 'mute_rule', rule_id: r.rule_id, muted: !r.muted });
      show(r.muted ? `${r.rule_id} faollashtirildi` : `${r.rule_id} susaytirildi (maslahat)`);
      reload();
    } catch (e) {
      show(`O‘zgartirib bo‘lmadi: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel className="gap-top">
      <PanelHead
        sub="Aniqlash qoidalari · barcha telefonlarga tarqaladi"
        title={`YARA-lite qoidalar (${rows.length})`}
      />
      <div className="body-pad">
        {isOwner && (
          <div className="row-inline" style={{ marginBottom: 12, flexWrap: 'wrap' }}>
            <input placeholder="qoida ID (ajina_sms_v1)" value={ruleId} onChange={(e) => setRuleId(e.target.value)} style={{ flex: '1 1 180px' }} />
            <input placeholder="Oila (ixtiyoriy)" value={family} onChange={(e) => setFamily(e.target.value)} style={{ flex: '1 1 140px' }} />
            <select value={severity} onChange={(e) => setSeverity(e.target.value)} style={{ width: 120 }}>
              <option value="low">Past</option>
              <option value="medium">O‘rta</option>
              <option value="high">Yuqori</option>
              <option value="critical">Kritik</option>
            </select>
            <select value={target} onChange={(e) => setTarget(e.target.value)} style={{ width: 140 }}>
              <option value="dex_string">DEX satri</option>
              <option value="manifest">Manifest</option>
              <option value="path">Fayl yo‘li</option>
            </select>
            <input type="number" min={0} placeholder="min" value={minHits} onChange={(e) => setMinHits(e.target.value)} style={{ width: 90 }} />
            <textarea
              placeholder="Kalitlar — vergul yoki har qatorga bittadan"
              value={needles}
              onChange={(e) => setNeedles(e.target.value)}
              rows={2}
              style={{ flex: '1 1 100%' }}
            />
            <button className="btn" onClick={add} disabled={busy || !ruleId.trim() || !needles.trim()}>
              {busy ? <span className="spinner" /> : '+ Qoida qo‘shish'}
            </button>
          </div>
        )}
        {loading && !rows.length ? (
          <Spinner label="Yuklanmoqda…" />
        ) : !rows.length ? (
          <Empty>Qoida yo‘q. Yuqoriga aniqlash qoidasi qo‘shing — barcha telefonlarga tarqaladi.</Empty>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table>
              <thead>
                <tr>
                  <th>Qoida</th>
                  <th>Oila</th>
                  <th>Nishon</th>
                  <th>Daraja</th>
                  <th className="num">Kalit</th>
                  <th className="num">Min</th>
                  {isOwner && <th />}
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.rule_id} style={r.muted ? { opacity: 0.55 } : undefined}>
                    <td className="mono" style={{ fontSize: 13 }}>
                      <b>{r.rule_id}</b>
                      {r.muted && <span style={{ marginLeft: 6 }}><Tag>advisory</Tag></span>}
                    </td>
                    <td>{r.family ? <Tag kind="comp">{r.family}</Tag> : <span style={{ color: 'var(--ink-3)' }}>—</span>}</td>
                    <td style={{ color: 'var(--ink-2)', fontSize: 12 }}>{TARGET_UZ[r.target] || r.target}</td>
                    <td>
                      <span style={{ color: SEV_COLOR[r.severity] || 'var(--warn)', fontWeight: 700, fontSize: 12 }}>
                        {SEV_UZ[r.severity] || r.severity}
                      </span>
                    </td>
                    <td className="num">{(r.needles || []).length}</td>
                    <td className="num">{r.min_hits}</td>
                    {isOwner && (
                      <td>
                        <div className="row-inline" style={{ justifyContent: 'flex-end', flexWrap: 'wrap', gap: 6 }}>
                          <button className="btn sm ghost" onClick={() => toggleMute(r)} disabled={busy}>
                            {r.muted ? 'Faollashtirish' : 'Susaytirish'}
                          </button>
                          <button className="btn sm danger" onClick={() => del(r.rule_id)} disabled={busy}>O‘chirish</button>
                        </div>
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

/**
 * Qoida statistikasi (FP paneli): har bir qoida necha marta ishga tushgan, nechta qurilmada,
 * nechtasi rad etilgan. Rad etish/ishlash nisbati yuqori (>0.3) bo'lsa — noto'g'ri ishlayotgan
 * bo'lishi mumkin, ogohlantiruvchi rangda ko'rsatamiz.
 */
function RuleStatsPanel() {
  const { data, loading } = usePoll(
    () => apiGet<{ stats: RuleStat[] }>('/api/threats?rulestats=1'),
    60000,
  );
  const rows = data?.stats || [];
  return (
    <Panel className="gap-top">
      <PanelHead sub="Noto‘g‘ri ishga tushish nazorati" title={`Qoida statistikasi (${rows.length})`} />
      <div className="body-pad">
        {loading && !rows.length ? (
          <Spinner label="Yuklanmoqda…" />
        ) : !rows.length ? (
          <Empty>Hali statistika yo‘q</Empty>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table>
              <thead>
                <tr>
                  <th>Qoida</th>
                  <th className="num">Ishladi</th>
                  <th className="num">Qurilma</th>
                  <th className="num">Rad etildi</th>
                  <th>Oxirgi</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => {
                  const ratio = r.fires ? r.dismissed / r.fires : 0;
                  const hot = ratio > 0.3;
                  return (
                    <tr key={r.rule_id}>
                      <td className="mono" style={{ fontSize: 13 }}><b>{r.rule_id}</b></td>
                      <td className="num">{r.fires}</td>
                      <td className="num">{r.devices}</td>
                      <td className="num" style={hot ? { color: 'var(--warn-ink)', fontWeight: 700 } : undefined}>
                        {r.dismissed}{hot ? ` (${Math.round(ratio * 100)}%)` : ''}
                      </td>
                      <td style={{ color: 'var(--ink-3)', fontSize: 12 }}>{agoSafe(r.last_fire)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </Panel>
  );
}

/**
 * Yaxshi ro'yxat (known_good). Bu yerdagi paket (+ixtiyoriy cert) faqat SUSPICIOUS verdisini
 * pasaytiradi — DANGER hech qachon pasaymaydi. Qo'shish/o'chirish FAQAT EGADA.
 */
function KnownGoodPanel() {
  const { isOwner } = useAuth();
  const { show } = useToast();
  const { data, loading, reload } = usePoll(
    () => apiGet<{ good: KnownGood[] }>('/api/threats?good=1'),
    60000,
  );
  const rows = data?.good || [];
  const [pkg, setPkg] = useState('');
  const [cert, setCert] = useState('');
  const [label, setLabel] = useState('');
  const [busy, setBusy] = useState(false);

  const add = async () => {
    const p = pkg.trim();
    const c = cert.trim().toLowerCase();
    if (!p || busy) return;
    if (c && !/^[0-9a-f]{64}$/.test(c)) { show('Sertifikat SHA-256 — 64 ta hex belgidan iborat bo‘lishi kerak'); return; }
    setBusy(true);
    try {
      await apiPost('/api/threats', { action: 'add_good', package_name: p, cert_sha256: c, label: label.trim() });
      setPkg(''); setCert(''); setLabel('');
      show(`${p} yaxshi ro‘yxatga qo‘shildi`);
      reload();
    } catch (e) {
      show(`Qo‘shib bo‘lmadi: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  const del = async (r: KnownGood) => {
    if (busy) return;
    setBusy(true);
    try {
      await apiPost('/api/threats', { action: 'delete_good', package_name: r.package_name, cert_sha256: r.cert_sha256 });
      show(`${r.package_name} ro‘yxatdan olib tashlandi`);
      reload();
    } catch (e) {
      show(`O‘chirib bo‘lmadi: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel className="gap-top">
      <PanelHead sub="Ishonchli ilovalar" title={`Yaxshi ro‘yxat (${rows.length})`} />
      <div className="body-pad">
        <div className="note ok" style={{ marginBottom: 12 }}>
          <span className="ni">✅</span>
          <span>Faqat SUSPICIOUS’ni pasaytiradi — DANGER’ni hech qachon emas.</span>
        </div>
        {isOwner && (
          <div className="row-inline" style={{ marginBottom: 12, flexWrap: 'wrap' }}>
            <input placeholder="paket nomi (com.example.app)" value={pkg} onChange={(e) => setPkg(e.target.value)} style={{ flex: '1 1 200px' }} />
            <input className="mono" placeholder="cert SHA-256 (ixtiyoriy, 64 hex)" value={cert} onChange={(e) => setCert(e.target.value)} style={{ flex: '1 1 200px' }} />
            <input placeholder="Yorliq (ixtiyoriy)" value={label} onChange={(e) => setLabel(e.target.value)} style={{ flex: '1 1 140px' }} />
            <button className="btn" onClick={add} disabled={busy || !pkg.trim()}>
              {busy ? <span className="spinner" /> : '+ Qo‘shish'}
            </button>
          </div>
        )}
        {loading && !rows.length ? (
          <Spinner label="Yuklanmoqda…" />
        ) : !rows.length ? (
          <Empty>Ro‘yxat bo‘sh</Empty>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table>
              <thead>
                <tr>
                  <th>Paket</th>
                  <th>Sertifikat</th>
                  <th>Yorliq</th>
                  <th>Qo‘shilgan</th>
                  {isOwner && <th />}
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.package_name + (r.cert_sha256 || '')}>
                    <td className="mono" style={{ fontSize: 13 }}><b>{r.package_name}</b></td>
                    <td className="mono" style={{ fontSize: 11, color: 'var(--ink-3)' }}>{r.cert_sha256 ? r.cert_sha256.slice(0, 16) + '…' : '—'}</td>
                    <td style={{ fontSize: 13 }}>{r.label || '—'}</td>
                    <td style={{ color: 'var(--ink-2)', fontSize: 12 }}>{agoSafe(r.created_at)}</td>
                    {isOwner && (
                      <td>
                        <button className="btn sm danger" onClick={() => del(r)} disabled={busy}>O‘chirish</button>
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
  const { isOwner } = useAuth();
  const { show } = useToast();
  const { data, loading, error, reload } = usePoll(() => apiGet<{ threats: ThreatFamily[] }>('/api/threats'), 20000);
  const list = data?.threats || [];
  const sorted = [...list].sort((a, b) => (b.seen_count || 0) - (a.seen_count || 0));
  const top = sorted.slice(0, 8);
  const max = Math.max(1, ...top.map((t) => t.seen_count || 0));

  const [q, setQ] = useState('');
  const [sev, setSev] = useState('');
  const [outbreakBusy, setOutbreakBusy] = useState<string | null>(null);
  const cols = isOwner ? 9 : 8; // to'liq ro'yxat jadvali ustunlari (Epidemiya ustuni faqat egada)
  const shown = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return sorted.filter((t) => {
      if (sev && (t.severity || 'low') !== sev) return false;
      if (!needle) return true;
      return [t.app_label, t.package_name, t.category, t.family].filter(Boolean).join(' ').toLowerCase().includes(needle);
    });
  }, [sorted, q, sev]);

  // "Epidemiya rejimi" (egasi): bir tugma bilan butun parkni ogohlantiramiz. Har bosqich
  // alohida try/catch — qisman muvaffaqiyat ham nima bo'lgani haqida hisobot beradi.
  const runOutbreak = async (t: ThreatFamily) => {
    const name = t.app_label || t.family || 'aniqlangan tahdid';
    if (!window.confirm(`Epidemiya rejimi: "${name}" bo‘yicha butun parkka ogohlantirish e‘lon qilinadi va zararlangan qurilmalarga qayta-skan yuboriladi. Davom etamizmi?`)) return;
    setOutbreakBusy(t.apk_hash);
    let newsOk = false; let sent = 0;
    try {
      // (a) qadab qo'yilgan kritik e'lon (auto-shablon)
      try {
        const cr = await apiPost<{ id?: string; news?: { id?: string }; item?: { id?: string } }>('/api/news', {
          action: 'create',
          title: `⚠️ Xavfli ilova: ${name}`,
          body: 'Bu ilova zararli deb topildi. Agar o‘rnatgan bo‘lsangiz — darhol o‘chirib tashlang.',
          level: 'critical',
        });
        newsOk = true;
        const nid = cr.id ?? cr.news?.id ?? cr.item?.id;
        if (nid) { try { await apiPost('/api/news', { action: 'toggle_pin', id: nid, pinned: true }); } catch { /* pin ixtiyoriy */ } }
      } catch { /* keyingi bosqichlar baribir ishlasin */ }

      // (b) oila yorlig'i (faqat bo'sh bo'lsa)
      if (!t.family) {
        try { await apiPost('/api/threats', { action: 'set_family', apk_hash: t.apk_hash, family: t.app_label || 'Epidemiya' }); }
        catch { /* ixtiyoriy */ }
      }

      // (c) zararlangan qurilmalarga qayta-skan (ketma-ket, 100 tagacha)
      try {
        const ids = (await apiGet<{ device_ids: string[] }>(`/api/scans?hash=${encodeURIComponent(t.apk_hash)}`)).device_ids || [];
        for (const did of ids.slice(0, 100)) {
          try { await apiPost(`/api/device/${did}`, { type: 'rescan' }); sent += 1; } catch { /* alohida xatoni o'tkazamiz */ }
        }
      } catch { /* ixtiyoriy */ }

      show(`Epidemiya rejimi: ${newsOk ? 'e‘lon joylandi' : 'e‘lon joylanmadi'}, ${sent} qurilmaga qayta-skan yuborildi.`);
      reload();
    } catch (e) {
      const m = (e as Error).message || '';
      show(/40[13]|ruxsat|forbidden|egas/i.test(m) ? 'Bu amal faqat egasida' : `Epidemiya rejimi xatosi: ${m}`);
    } finally {
      setOutbreakBusy(null);
    }
  };

  return (
    <>
      <div className="page-intro">
        <h1>Eng faol tahdidlar</h1>
        <p>Aniqlangan zararli ilovalar oilalari — necha marta uchragani, toifasi va xavf darajasi bo‘yicha.</p>
      </div>

      {isOwner && <ReviewQueue items={list} onChanged={reload} />}

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
        <div className="body-pad" style={{ paddingBottom: 0 }}>
          <div className="list-filter">
            <input
              className="search"
              placeholder="Qidirish: ilova yoki paket nomi…"
              value={q}
              onChange={(e) => setQ(e.target.value)}
            />
            <select value={sev} onChange={(e) => setSev(e.target.value)} style={{ width: 140 }}>
              <option value="">Barcha daraja</option>
              <option value="critical">Kritik</option>
              <option value="high">Yuqori</option>
              <option value="medium">O‘rta</option>
              <option value="low">Past</option>
            </select>
            {(q || sev) && <span className="lf-count">{shown.length} / {list.length}</span>}
          </div>
        </div>
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
                {isOwner && <th>Epidemiya</th>}
              </tr>
            </thead>
            <tbody>
              {error && !list.length ? (
                <tr><td colSpan={cols}><Empty>Yuklab bo‘lmadi: {error}</Empty></td></tr>
              ) : !sorted.length ? (
                <tr><td colSpan={cols}><Empty /></td></tr>
              ) : !shown.length ? (
                <tr><td colSpan={cols}><Empty>Filtrga mos tahdid topilmadi</Empty></td></tr>
              ) : (
                shown.map((t) => (
                  <tr key={t.apk_hash}>
                    <td>
                      <b>{t.app_label || '—'}</b>
                      {t.family && <><br /><Tag kind="comp">{t.family}</Tag></>}
                      {t.review_status === 'dismissed' && <span style={{ marginLeft: 6, fontSize: 11, color: 'var(--ink-3)' }}>· rad etilgan</span>}
                    </td>
                    <td className="mono" style={{ color: 'var(--ink-3)', fontSize: 12 }}>{t.package_name || '—'}</td>
                    <td><Tag kind="comp">{catUz(t.category)}</Tag></td>
                    <td>
                      <span style={{ color: SEV_COLOR[t.severity || 'low'] || '#1A9E54', fontWeight: 700, fontSize: 12 }}>
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
                    {isOwner && (
                      <td>
                        <button
                          className="btn sm danger"
                          onClick={() => runOutbreak(t)}
                          disabled={outbreakBusy === t.apk_hash}
                          title="Butun parkka ogohlantirish e‘lon qilish + zararlangan qurilmalarga qayta-skan"
                        >
                          {outbreakBusy === t.apk_hash ? <span className="spinner" /> : '🚨 Epidemiya'}
                        </button>
                      </td>
                    )}
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </Panel>

      <DomainsPanel />
      <RulesPanel />
      <RuleStatsPanel />
      <KnownGoodPanel />
    </>
  );
}
