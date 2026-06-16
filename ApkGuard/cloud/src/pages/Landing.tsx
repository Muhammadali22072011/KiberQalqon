import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { apiGet } from '../lib/api';

const FEATURES = [
  {
    icon: '🧬',
    title: 'Ko‘p qatlamli aniqlash',
    text: 'Dropper, SMS-o‘g‘ri, josus va bank troyanlarini 8+ analizator orqali aniqlaydi — real o‘zbek namunalari asosida tuzilgan.',
  },
  {
    icon: '🗺️',
    title: 'Jonli geo-monitoring',
    text: 'O‘zbekiston bo‘ylab himoyalangan qurilmalar va tahdidlar xaritada real vaqtda yonib turadi.',
  },
  {
    icon: '📡',
    title: 'Real vaqt oqimi',
    text: 'Har bir skan natijasi soniyalar ichida panelga tushadi — xavfli ilovalar darhol ko‘rinadi.',
  },
  {
    icon: '🔐',
    title: 'Egasi + admin kirishi',
    text: 'Egasi master kalit (+2FA) bilan to‘liq huquqqa, bitta cheklangan admin esa login+parol bilan ko‘rish/eksportga kiradi. Token soatlik — kalit brauzerda saqlanmaydi.',
  },
  {
    icon: '🛡',
    title: 'Buzib bo‘lmas himoya',
    text: 'APK teskari muhandislik qilinsa ham master kalit ichida yo‘q. Server kalitlari brauzerga umuman chiqmaydi.',
  },
  {
    icon: '📰',
    title: 'E‘lonlar lentasi',
    text: 'Ogohlantirish va yangiliklar to‘g‘ridan-to‘g‘ri foydalanuvchining ilovasiga yetkaziladi.',
  },
];

interface PubStats {
  devices: number;
  scans: number;
  blocked: number;
  regionsCount: number;
  regions: Array<{ region: string; devices: number; danger: number }>;
}

const fmt = (n: number) => n.toLocaleString('ru-RU');

export default function Landing() {
  const [pub, setPub] = useState<PubStats | null>(null);

  useEffect(() => {
    // Ochiq sahifa — xato bo'lsa bir necha bor qayta urinamiz (oldin "statik raqamlar"
    // deb yozilgan edi, lekin kodda statik son yo'q — barcha hero raqamlar abadiy "—" qolardi).
    let alive = true;
    let tries = 0;
    const attempt = () => {
      apiGet<{ pub: PubStats }>('/api/stats?public=1')
        .then((r) => { if (alive) setPub(r.pub); })
        .catch(() => { if (alive && tries++ < 3) window.setTimeout(attempt, 2000); });
    };
    attempt();
    return () => { alive = false; };
  }, []);

  return (
    <div className="landing">
      <div className="lwrap">
        <nav className="lnav">
          <div className="logo">🛡</div>
          <div className="brand"><b>UzGuard</b><small>Cloud · himoya markazi</small></div>
          <div className="spacer" />
          <Link to="/login" className="btn ghost">Panelga kirish</Link>
        </nav>

        <header className="lhero">
          <div className="lbadge">● O‘zbekiston · Android xavfsizlik</div>
          <h1>
            Android tahdidlarni <span className="accent">real vaqtda</span> ko‘rib turing
          </h1>
          <p>
            UzGuard — O‘zbekiston foydalanuvchilariga qaratilgan bank troyanlari, dropperlar va
            josus ilovalarni aniqlaydigan, ularni xaritada va jonli oqimda kuzatib boruvchi himoya tizimi.
          </p>
          <div className="lcta">
            <Link to="/login" className="btn lg">Boshqaruv paneli →</Link>
            <a className="btn ghost lg" href="#feat">Imkoniyatlar</a>
          </div>
        </header>

        {/* Jonli himoya raqamlari — bazadan (ochiq, auth'siz yig'ma sonlar) */}
        <section className="lstats">
          <div className="lstat">
            <b>{pub ? fmt(pub.blocked) : '—'}</b><span>bloklangan tahdid</span>
          </div>
          <div className="lstat">
            <b>{pub ? fmt(pub.devices) : '—'}</b><span>himoyalangan qurilma</span>
          </div>
          <div className="lstat">
            <b>{pub ? fmt(pub.scans) : '—'}</b><span>o‘tkazilgan tekshiruv</span>
          </div>
          <div className="lstat">
            <b>{pub ? pub.regionsCount : '—'}</b><span>qamrab olingan hudud</span>
          </div>
        </section>

        {pub && pub.regions.length > 0 && (
          <section className="lregions">
            <h3>Hududlar bo‘yicha qamrov</h3>
            <div className="lreg-grid">
              {pub.regions.map((r) => (
                <div className="lreg" key={r.region}>
                  <div className="lreg-name">{r.region}</div>
                  <div className="lreg-meta">
                    <span>{fmt(r.devices)} qurilma</span>
                    {r.danger > 0 && <span className="lreg-danger">{fmt(r.danger)} xavfli</span>}
                  </div>
                </div>
              ))}
            </div>
          </section>
        )}

        <section className="lfeatures" id="feat">
          {FEATURES.map((f) => (
            <div className="lfeat" key={f.title}>
              <div className="lf-ico">{f.icon}</div>
              <h3>{f.title}</h3>
              <p>{f.text}</p>
            </div>
          ))}
        </section>

        <section className="lstats">
          <div className="lstat"><b>8+</b><span>aniqlash qatlami</span></div>
          <div className="lstat"><b>24/7</b><span>real vaqt kuzatuvi</span></div>
          <div className="lstat"><b>0</b><span>kalit APK ichida</span></div>
          <div className="lstat"><b>100%</b><span>o‘zbek tilida</span></div>
        </section>

        <footer className="lfoot">© 2026 · UzGuard · Muhammadali</footer>
      </div>
    </div>
  );
}
