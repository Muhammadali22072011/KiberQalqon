import { useEffect, useMemo, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { apiGet, type NewsItem } from '../lib/api';
import { usePoll } from '../hooks/usePoll';
import { hms } from '../lib/format';
import { exportAllToExcel } from '../lib/exportExcel';
import { useToast } from './Toast';

// Egasi va admin — ikkalasi ham hamma bo'limni ko'radi.
const NAV = [
  { to: '/app', end: true, icon: '📊', label: 'Bosh sahifa' },
  { to: '/app/map', icon: '🗺️', label: 'Geo xarita' },
  { to: '/app/feed', icon: '📡', label: 'Jonli oqim' },
  { to: '/app/threats', icon: '🧬', label: 'Tahdidlar' },
  { to: '/app/devices', icon: '📱', label: 'Qurilmalar' },
  { to: '/app/news', icon: '📰', label: "E'lonlar" },
  { to: '/app/profile', icon: '👤', label: 'Profil' },
] as const;

const TITLES: Record<string, { sub: string; title: string }> = {
  '/app': { sub: 'Umumiy ko‘rinish', title: 'Bosh sahifa' },
  '/app/map': { sub: 'Geo monitoring', title: "O‘zbekiston himoya xaritasi" },
  '/app/feed': { sub: 'Real vaqt', title: 'Jonli tahdidlar oqimi' },
  '/app/threats': { sub: 'Tahlil', title: 'Eng faol tahdidlar' },
  '/app/devices': { sub: 'Qurilmalar', title: 'Himoyalangan qurilmalar' },
  '/app/news': { sub: 'E‘lonlar · lenta', title: 'Yangiliklar' },
  '/app/profile': { sub: 'Hisob', title: 'Profil va xavfsizlik' },
};

export default function Layout() {
  const { logout } = useAuth();
  const { show } = useToast();
  const nav = useNavigate();
  const loc = useLocation();
  const [open, setOpen] = useState(false);
  const [clock, setClock] = useState(hms());
  const [exporting, setExporting] = useState(false);
  // kq_news_seen localStorage'da; React uni ko'rmaydi, shuning uchun state'da kuzatamiz
  // (memo qayta hisoblansin, e'lonlar o'qilgach badge darhol tozalansin).
  const [seenAt, setSeenAt] = useState<number>(() => Number(localStorage.getItem('kq_news_seen') || 0));

  const doExport = async () => {
    if (exporting) return;
    setExporting(true);
    try {
      await exportAllToExcel();
      show('Excel fayl tayyor');
    } catch {
      show('Eksport amalga oshmadi');
    } finally {
      setExporting(false);
    }
  };

  useEffect(() => {
    const id = window.setInterval(() => setClock(hms()), 1000);
    return () => window.clearInterval(id);
  }, []);

  useEffect(() => { setOpen(false); }, [loc.pathname]);

  // Marshrut o'zgarganda kq_news_seen'ni qayta o'qiymiz: shu tabda /app/news ochilganda
  // News.tsx mount-effekti seen vaqtini yozadi (bola effektlari ota'dan oldin ishlaydi),
  // shuning uchun bu yerda o'qisak badge darhol tozalanadi (storage event kerak emas).
  useEffect(() => { setSeenAt(Number(localStorage.getItem('kq_news_seen') || 0)); }, [loc.pathname]);

  // kq_news_seen o'zgarganini sezish: boshqa tab → 'storage'; shu tab (NewsCarousel /
  // News sahifasi setItem qiladi, 'storage' otmaydi) → focus/visibilitychange'da qayta o'qiymiz.
  useEffect(() => {
    const sync = () => setSeenAt(Number(localStorage.getItem('kq_news_seen') || 0));
    const onStorage = (e: StorageEvent) => { if (e.key === 'kq_news_seen') sync(); };
    const onVisible = () => { if (document.visibilityState === 'visible') sync(); };
    window.addEventListener('storage', onStorage);
    window.addEventListener('focus', sync);
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      window.removeEventListener('storage', onStorage);
      window.removeEventListener('focus', sync);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, []);

  // Egasi va admin — ikkalasi ham hamma bo'limni ko'radi.
  const items = NAV;
  const showNews = true;

  const news = usePoll(
    () => (showNews ? apiGet<{ news: NewsItem[] }>('/api/news') : Promise.resolve({ news: [] })),
    60000,
  );
  const unread = useMemo(() => (
    (news.data?.news || []).filter(
      (n) => (new Date(n.created_at).getTime() || 0) > seenAt,
    ).length
  ), [news.data, seenAt]);
  const badge = unread > 99 ? '99+' : String(unread);

  const head = TITLES[loc.pathname] || { sub: 'KiberQalqon', title: 'Panel' };

  const doLogout = () => { logout(); nav('/'); };

  return (
    <div className={'shell' + (open ? ' nav-open' : '')}>
      <aside className="sidebar">
        <div className="side-brand">
          <div className="logo">🛡</div>
          <div className="brand"><b>KiberQalqon</b><small>Cloud panel</small></div>
        </div>
        <nav className="side-nav">
          {items.map((n) => (
            <NavLink
              key={n.to}
              to={n.to}
              end={'end' in n ? n.end : false}
              className={({ isActive }) => 'nav-item' + (isActive ? ' active' : '')}
            >
              <span className="ni-ico">{n.icon}</span>
              <span className="ni-label">{n.label}</span>
              {n.to === '/app/news' && unread > 0 && <span className="ni-badge">{badge}</span>}
            </NavLink>
          ))}
        </nav>
        <div className="side-foot">
          <button className="side-logout" onClick={doLogout}>⎋ Chiqish</button>
          <div className="side-copy">© 2026 · Muhammadali</div>
        </div>
      </aside>

      <div className="main">
        <header className="topbar">
          <button className="burger" onClick={() => setOpen((o) => !o)} aria-label="Menyu">☰</button>
          <div className="brand head"><small>{head.sub}</small><b>{head.title}</b></div>
          <div className="spacer" />
          <span className="pill"><span className="live-dot" /> JONLI · {clock}</span>
          <button className="icon-btn" title="Excel'ga eksport" onClick={doExport} disabled={exporting}>
            {exporting ? <span className="spinner" /> : '⬇'}
          </button>
          {showNews && (
            <button className="icon-btn" title="E‘lonlar" onClick={() => nav('/app/news')}>
              📰{unread > 0 && <span className="badge">{badge}</span>}
            </button>
          )}
          <button className="icon-btn" title="Chiqish" onClick={doLogout}>⎋</button>
        </header>
        <main className="content"><Outlet /></main>
      </div>

      {open && <div className="scrim" onClick={() => setOpen(false)} />}
    </div>
  );
}
