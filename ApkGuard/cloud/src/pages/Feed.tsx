import { useEffect, useMemo, useRef, useState } from 'react';
import { usePoll } from '../hooks/usePoll';
import { apiGet, type FeedItem } from '../lib/api';
import { Empty, LivePill, Panel, PanelHead, Spinner, VerdictBadge } from '../components/ui';
import { agoSafe, VERDICT_DOT } from '../lib/format';

type Filter = 'all' | 'danger' | 'suspicious';
const keyOf = (f: FeedItem) => (f.apk_hash || '') + '|' + f.scanned_at;

export default function Feed() {
  const { data, loading, error } = usePoll(() => apiGet<{ feed: FeedItem[] }>('/api/feed'), 6000);
  const all = useMemo(() => data?.feed || [], [data]);
  const [filter, setFilter] = useState<Filter>('all');

  // Yangi yozuvlarni "fresh" deb belgilab, qisqa animatsiya beramiz.
  const seen = useRef<Set<string>>(new Set());
  const inited = useRef(false);
  const [fresh, setFresh] = useState<Set<string>>(new Set());

  useEffect(() => {
    if (!all.length) return;
    const keys = all.map(keyOf);
    if (!inited.current) {
      keys.forEach((k) => seen.current.add(k));
      inited.current = true;
      return;
    }
    const added = keys.filter((k) => !seen.current.has(k));
    if (added.length) {
      added.forEach((k) => seen.current.add(k));
      setFresh(new Set(added));
      const t = window.setTimeout(() => setFresh(new Set()), 1600);
      return () => window.clearTimeout(t);
    }
  }, [all]);

  const items = filter === 'all' ? all : all.filter((f) => f.verdict === filter);

  const FILTERS: { k: Filter; label: string }[] = [
    { k: 'all', label: 'Hammasi' },
    { k: 'danger', label: 'Xavfli' },
    { k: 'suspicious', label: 'Shubhali' },
  ];

  return (
    <>
      <div className="page-intro">
        <h1>Jonli tahdidlar oqimi</h1>
        <p>Barcha qurilmalardan kelayotgan xavfli va shubhali skanlar real vaqtda shu yerda paydo bo‘ladi.</p>
      </div>

      <Panel>
        <PanelHead
          sub="Real vaqt · 6 soniyada yangilanadi"
          title={`${items.length} ta yozuv`}
          right={
            <div className="row-inline">
              {FILTERS.map((f) => (
                <button
                  key={f.k}
                  className={'btn ghost' + (filter === f.k ? ' active' : '')}
                  style={filter === f.k ? { color: 'var(--primary)', borderColor: 'var(--primary)' } : undefined}
                  onClick={() => setFilter(f.k)}
                >
                  {f.label}
                </button>
              ))}
              <LivePill />
            </div>
          }
        />
        <div className="feed-scroll tall">
          {loading && !all.length ? (
            <Spinner label="Oqim ulanmoqda…" />
          ) : error && !all.length ? (
            <Empty>Oqimni yuklab bo‘lmadi: {error}</Empty>
          ) : !items.length ? (
            <Empty>Tanlovga mos yozuv yo‘q</Empty>
          ) : (
            items.map((f) => {
              const k = keyOf(f);
              return (
                <div className={'fi' + (fresh.has(k) ? ' fresh' : '')} key={k}>
                  <span className="sev" style={{ color: VERDICT_DOT[f.verdict] || '#9aa7c2' }} />
                  <div className="fi-main">
                    <div className="fi-app">{f.app_label || f.package_name || 'Nomaʼlum ilova'}</div>
                    <div className="fi-meta">
                      {[f.package_name, f.city, agoSafe(f.scanned_at)].filter(Boolean).join(' · ')}
                    </div>
                  </div>
                  <VerdictBadge verdict={f.verdict} />
                </div>
              );
            })
          )}
        </div>
      </Panel>
    </>
  );
}
