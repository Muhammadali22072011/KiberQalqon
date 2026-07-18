import { usePoll } from '../hooks/usePoll';
import { apiGet, type AuditRow } from '../lib/api';
import { Empty, Panel, PanelHead, Spinner } from '../components/ui';
import { agoSafe } from '../lib/format';

// Panel amallari jurnali (admin_audit_log, migratsiya 15) — FAQAT EGASI ko'radi
// (marshrut Layout'da isOwner bilan yashirinadi; server ham 403 bilan himoyalaydi).
const ACTION_UZ: Record<string, { label: string; color: string }> = {
  login: { label: 'Kirish', color: 'var(--ok)' },
  login_fail: { label: 'Kirish xatosi', color: 'var(--danger)' },
  news_create: { label: "E'lon joylandi", color: 'var(--info)' },
  news_delete: { label: "E'lon o'chirildi", color: 'var(--warn)' },
  news_pin: { label: "E'lon qadaldi", color: 'var(--info)' },
  domain_add: { label: 'Domen bloklandi', color: 'var(--danger)' },
  domain_del: { label: 'Domen olib tashlandi', color: 'var(--warn)' },
};

export default function Audit() {
  const { data, loading, error } = usePoll(
    () => apiGet<{ audit: AuditRow[] }>('/api/stats?audit=1'),
    30000,
  );
  const rows = data?.audit || [];

  return (
    <>
      <div className="page-intro">
        <h1>Amallar jurnali</h1>
        <p>Panelga kim qachon kirgani va nima qilgani — xavfsizlik auditi uchun. Faqat egasi ko‘radi.</p>
      </div>

      <Panel>
        <PanelHead sub="Audit" title={`Oxirgi ${rows.length} ta amal`} />
        <div style={{ overflowX: 'auto' }}>
          <table>
            <thead>
              <tr>
                <th>Qachon</th>
                <th>Kim</th>
                <th>Amal</th>
                <th>Tafsilot</th>
                <th>IP</th>
              </tr>
            </thead>
            <tbody>
              {loading && !rows.length ? (
                <tr><td colSpan={5}><Spinner label="Yuklanmoqda…" /></td></tr>
              ) : error && !rows.length ? (
                <tr><td colSpan={5}><Empty>Yuklab bo‘lmadi: {error}</Empty></td></tr>
              ) : !rows.length ? (
                <tr><td colSpan={5}><Empty>Jurnal hozircha bo‘sh</Empty></td></tr>
              ) : (
                rows.map((r) => {
                  const a = ACTION_UZ[r.action] || { label: r.action, color: 'var(--ink-2)' };
                  return (
                    <tr key={r.id}>
                      <td style={{ color: 'var(--ink-2)', fontSize: 12, whiteSpace: 'nowrap' }}>{agoSafe(r.at)}</td>
                      <td className="mono" style={{ fontSize: 12 }}>
                        {r.actor === 'owner' ? '👑 Egasi' : r.actor.startsWith('admin:') ? `👤 ${r.actor.slice(6)}` : r.actor}
                      </td>
                      <td><b style={{ color: a.color, fontSize: 13 }}>{a.label}</b></td>
                      <td style={{ color: 'var(--ink-2)', fontSize: 12, maxWidth: 360, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {r.detail || '—'}
                      </td>
                      <td className="mono" style={{ color: 'var(--ink-3)', fontSize: 11 }}>{r.ip || '—'}</td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      </Panel>
    </>
  );
}
