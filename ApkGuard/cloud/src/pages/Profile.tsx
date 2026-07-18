import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { Panel, PanelHead } from '../components/ui';
import { usePoll } from '../hooks/usePoll';
import { apiGet, type AppUpdateInfo } from '../lib/api';

const SECURITY = [
  { icon: '🔑', title: 'Master kalit', text: 'ADMIN_SECRET hech qachon APK ichida emas — faqat panel kirishida ishlatiladi va brauzerda saqlanmaydi.' },
  { icon: '⏱', title: 'Qisqa sessiya', text: 'Kirgach faqat qisqa muddatli token beriladi; u sessiya tugashi bilan o‘chadi.' },
  { icon: '📱', title: 'Qurilma siri', text: 'APK ichidagi DEVICE_SHARED_SECRET faqat o‘z maʼlumotini yuborish uchun — boshqalarnikini ko‘ra olmaydi.' },
  { icon: '🛰', title: 'Server kalitlari', text: 'Supabase va Telegram kalitlari faqat serverda (Vercel env) — brauzerga umuman chiqmaydi.' },
  { icon: '🔒', title: 'Qat‘iy CSP', text: 'Sahifa qat‘iy Content-Security-Policy bilan himoyalangan — begona skript yuklanmaydi.' },
];

export default function Profile() {
  const { isOwner, name, logout } = useAuth();
  const nav = useNavigate();
  const doLogout = () => { logout(); nav('/'); };

  return (
    <>
      <div className="page-intro">
        <h1>Profil va xavfsizlik</h1>
        <p>Hisob holati va tizim himoyasining qisqacha ko‘rinishi.</p>
      </div>

      <div className="grid map-grid">
        {isOwner ? (
          <Panel>
            <PanelHead sub="Hisob" title="Egasi (Super-admin)" />
            <div className="body-pad">
              <div className="pp-rows" style={{ borderTop: 'none' }}>
                <div><span>Rol</span><b>Egasi · to‘liq huquq</b></div>
                <div><span>Sessiya</span><b style={{ color: 'var(--primary)' }}>Faol</b></div>
                <div><span>Kirish usuli</span><b>Master kalit (+2FA)</b></div>
                <div><span>Til</span><b>O‘zbekcha</b></div>
              </div>
              <div className="note ok" style={{ marginTop: 14 }}>
                <span className="ni">✓</span>
                <span>Siz to‘liq huquqli egasiz: e‘lonlar, qurilmalar va tahdidlarning barchasini boshqarasiz.</span>
              </div>
              <button className="btn danger block" style={{ marginTop: 14 }} onClick={doLogout}>
                ⎋ Tizimdan chiqish
              </button>
            </div>
          </Panel>
        ) : (
          <Panel>
            <PanelHead sub="Hisob" title="Admin" />
            <div className="body-pad">
              <div className="pp-rows" style={{ borderTop: 'none' }}>
                <div><span>Hisob</span><b>{name || 'Admin'}</b></div>
                <div><span>Sessiya</span><b style={{ color: 'var(--primary)' }}>Faol</b></div>
                <div><span>Kirish usuli</span><b>Login + parol</b></div>
                <div><span>Til</span><b>O‘zbekcha</b></div>
              </div>
              <div className="note" style={{ marginTop: 14 }}>
                <span className="ni">ℹ️</span>
                <span>Siz hamma ma‘lumotni ko‘rasiz, eksport qilasiz va e‘lon joylaysiz. Ma‘lumotni o‘zgartirish/o‘chirish faqat egada.</span>
              </div>
              <button className="btn danger block" style={{ marginTop: 14 }} onClick={doLogout}>
                ⎋ Tizimdan chiqish
              </button>
            </div>
          </Panel>
        )}

        <Panel>
          <PanelHead sub="Himoya" title="Xavfsizlik holati" />
          <div className="body-pad">
            <div className="news-list">
              {SECURITY.map((s) => (
                <div className="note" key={s.title} style={{ alignItems: 'flex-start' }}>
                  <span className="ni">{s.icon}</span>
                  <span><b style={{ color: 'var(--ink)' }}>{s.title}.</b> {s.text}</span>
                </div>
              ))}
            </div>
            <UpdateCard />
          </div>
        </Panel>
      </div>
    </>
  );
}

/**
 * Ilova yangilanishi holati: hozir telefonlarga qaysi versiya tarqatilayotganini
 * ko'rsatadi (/api/config?app=1 → UPDATE_* env). Sozlanmagan bo'lsa — yo'riqnoma.
 */
function UpdateCard() {
  const { data, loading } = usePoll(
    () => apiGet<{ update: AppUpdateInfo | null; configVersion: number }>('/api/config?app=1'),
    120000,
  );
  if (loading && !data) return null;
  const u = data?.update;
  return (
    <div className={'note ' + (u ? 'ok' : '')} style={{ marginTop: 12, alignItems: 'flex-start' }}>
      <span className="ni">📦</span>
      {u ? (
        <span>
          <b style={{ color: 'var(--ink)' }}>Ilova yangilanishi faol.</b>{' '}
          Telefonlarga tarqatilayotgan versiya: <b className="mono">build {u.versionCode}</b>{' '}
          (config v{data?.configVersion}). Yangi telefonlar ochilganda «Yangi versiya chiqdi» oladi.
        </span>
      ) : (
        <span>
          <b style={{ color: 'var(--ink)' }}>Ilova yangilanishi sozlanmagan.</b>{' '}
          Yangi versiya chiqarish: <span className="mono">scripts/publish_update.sh</span>{' '}
          (UPDATE_* env + redeploy) — RUCHNYE_SHAGI_VLADELCA.md §7.
        </span>
      )}
    </div>
  );
}
