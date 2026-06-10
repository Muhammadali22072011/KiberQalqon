import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { usePoll } from '../hooks/usePoll';
import { apiGet, type NewsItem } from '../lib/api';
import { uzDateSafe } from '../lib/format';
import { Empty, Panel, PanelHead, Spinner } from './ui';

const LEVEL_UZ: Record<string, string> = {
  info: 'E‘lon', warning: 'Ogohlantirish', critical: 'Muhim',
};

// Bosh sahifadagi yangiliklar lentasi: avtomatik aylanuvchi slayd-shou.
// Slaydga bosilsa — to‘liq matn + rasm modalda ochiladi, u yerda hamma
// e‘lonlarni varaqlash mumkin (‹ › yoki ←/→, nuqtalar bo‘ylab).
export default function NewsCarousel() {
  const { data, loading, error } = usePoll(() => apiGet<{ news: NewsItem[] }>('/api/news'), 30000);
  const items = useMemo(() => (data?.news || []).slice(0, 10), [data]);
  const len = items.length;

  const [idx, setIdx] = useState(0);
  const [open, setOpen] = useState(false);
  const [paused, setPaused] = useState(false);

  // E‘lon o‘chsa/qo‘shilsa indeks diapazondan chiqib ketmasin.
  useEffect(() => { if (idx >= len && len > 0) setIdx(0); }, [len, idx]);

  // Avtomatik aylanish — har 5 soniya. Modal ochiq yoki sichqoncha ustida — to‘xtaydi.
  useEffect(() => {
    if (open || paused || len <= 1) return;
    const id = window.setInterval(() => setIdx((i) => (i + 1) % len), 5000);
    return () => window.clearInterval(id);
  }, [open, paused, len]);

  const go = (d: number) => setIdx((i) => (i + d + len) % len);
  const openAt = (i: number) => {
    setIdx(i);
    setOpen(true);
    // O‘qildi deb belgilaymiz — sidebar/topbardagi belgisi tushadi.
    localStorage.setItem('kq_news_seen', String(Date.now()));
  };

  // Modal ochiq bo‘lganda klaviatura: Esc — yopish, ←/→ — varaqlash.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
      else if (e.key === 'ArrowLeft') go(-1);
      else if (e.key === 'ArrowRight') go(1);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, len]);

  const cur = items[idx];
  const lvl = (n: NewsItem) => LEVEL_UZ[n.level || 'info'] || 'E‘lon';

  return (
    <Panel className="gap-top">
      <PanelHead
        sub="Yangiliklar"
        title="E‘lonlar lentasi"
        right={<Link className="btn ghost" to="/app/news">Hammasi</Link>}
      />

      {loading && !len ? (
        <Spinner label="Yuklanmoqda…" />
      ) : error && !len ? (
        <Empty>E‘lonlarni yuklab bo‘lmadi — qayta urinilmoqda…</Empty>
      ) : !len ? (
        <Empty>Hali e‘lon yo‘q</Empty>
      ) : (
        <div
          className="carousel"
          onMouseEnter={() => setPaused(true)}
          onMouseLeave={() => setPaused(false)}
        >
          <div className="car-stage">
            {items.map((n, i) => (
              <button
                type="button"
                key={n.id}
                className={'car-slide' + (i === idx ? ' on' : '')}
                onClick={() => openAt(i)}
                tabIndex={i === idx ? 0 : -1}
                title={n.title}
              >
                {n.image_url
                  ? <img className="car-img" src={n.image_url} alt="" loading="lazy" />
                  : <div className="car-img ph"><span>📰</span></div>}
                <div className="car-shade" />
                <div className="car-info">
                  <span className={'lvl-chip lv-' + (n.level || 'info')}>{lvl(n)}</span>
                  <h3 className="car-title">{n.title}</h3>
                  {n.body && <p className="car-snip">{n.body}</p>}
                  <span className="car-date">{uzDateSafe(n.created_at)}</span>
                </div>
              </button>
            ))}

            {len > 1 && (
              <>
                <button className="car-nav prev" onClick={() => go(-1)} aria-label="Oldingi">‹</button>
                <button className="car-nav next" onClick={() => go(1)} aria-label="Keyingi">›</button>
              </>
            )}
          </div>

          {len > 1 && (
            <div className="car-dots">
              {items.map((n, i) => (
                <button
                  key={n.id}
                  className={'car-dot' + (i === idx ? ' on' : '')}
                  onClick={() => setIdx(i)}
                  aria-label={`E‘lon ${i + 1}`}
                />
              ))}
            </div>
          )}
        </div>
      )}

      {open && cur && (
        <div className="news-modal" onClick={() => setOpen(false)}>
          <div className="nm-card" onClick={(e) => e.stopPropagation()}>
            <button className="nm-close" onClick={() => setOpen(false)} aria-label="Yopish">✕</button>

            {cur.image_url
              ? <img className="nm-img" src={cur.image_url} alt="" />
              : <div className="nm-img ph"><span>📰</span></div>}

            <div className="nm-body">
              <span className={'lvl-chip lv-' + (cur.level || 'info')}>{lvl(cur)}</span>
              <h2 className="nm-title">{cur.title}</h2>
              <span className="nm-date">{uzDateSafe(cur.created_at)}</span>
              {cur.body && <p className="nm-text">{cur.body}</p>}
            </div>

            {len > 1 && (
              <>
                <button className="nm-nav prev" onClick={() => go(-1)} aria-label="Oldingi">‹</button>
                <button className="nm-nav next" onClick={() => go(1)} aria-label="Keyingi">›</button>
                <div className="nm-count">{idx + 1} / {len}</div>
              </>
            )}
          </div>
        </div>
      )}
    </Panel>
  );
}
