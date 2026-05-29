import { useEffect, useMemo, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { apiGet, type NewsItem } from '../lib/api';
import { usePoll } from '../hooks/usePoll';
import { hms } from '../lib/format';

const NAV = [
  { to: '/app', end: true, icon: '📊', label: 'Bosh sahifa' },
  { to: '/app/map', icon: '🗺️', label: 'Geo xarita' },
  { to: '/app/feed', icon: '📡', label: 'Jonli oqim' },
  { to: '/app/threats', icon: '🧬', label: 'Tahdidlar' },
  { to: '/app/devices', icon: '📱', label: 'Qurilmalar' },
  { to: '/app/roles', icon: '🔐', label: 'Rollar' },
  { to: '/app/news', icon: '📰', label: "E'lonlar" },
  { to: '/app/profile', icon: '👤', label: 'Profil' },
];

const TITLES: Record<string, { sub: string; title: string }> = {
  '/app': { sub: 'Umumiy ko‘rinish', title: 'Bosh sahifa' },
  '/app/map': { sub: 'Geo monitoring', title: "O‘zbekiston himoya xaritasi" },
  '/app/feed': { sub: 'Real vaqt', title: 'Jonli tahdidlar oqimi' },
  '/app/threats': { sub: 'Tahlil', title: 'Eng faol tahdidlar' },
  '/app/devices': { sub: 'Qurilmalar', title: 'Himoyalangan qurilmalar' },
  '/app/roles': { sub: 'Maxfiy kirish', title: 'Rollar va operatorlar' },
  '/app/news': { sub: 'E‘lonlar · lenta', title: 'Yangiliklar' },
  '/app/profile': { sub: 'Hisob', title: 'Profil va xavfsizlik' },
};

export default function Layout() {
  const { logout } = useAuth();
  const nav = useNavigate();
  const loc = useLocation();
  const [open, setOpen] = useState(false);
  const [clock, setClock] = useState(hms());

  useEffect(() => {
    const id = window.setInterval(() => setClock(hms()), 1000);
    return () => window.clearInterval(id);
  }, []);

  useEffect(() => { setOpen(false); }, [loc.pathname]);

  const news = usePoll(() => apiGet<{ news: NewsItem[] }>('/api/news'), 60000);
  const unread = useMemo(() => {
    const seen = Number(localStorage.getItem('kq_news_seen') || 0);
    return (news.data?.news || []).filter(
      (n) => (new Date(n.created_at).getTime() || 0) > seen,
    ).length;
  }, [news.data]);
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
          {NAV.map((n) => (
            <NavLink
              key={n.to}
              to={n.to}
              end={n.end}
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
          <button className="icon-btn" title="E‘lonlar" onClick={() => nav('/app/news')}>
            📰{unread > 0 && <span className="badge">{badge}</span>}
          </button>
          <button className="icon-btn" title="Chiqish" onClick={doLogout}>⎋</button>
        </header>
        <main className="content"><Outlet /></main>
      </div>

      {open && <div className="scrim" onClick={() => setOpen(false)} />}
    </div>
  );
}
