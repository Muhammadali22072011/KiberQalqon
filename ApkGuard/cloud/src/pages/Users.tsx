import { useMemo, useState } from 'react';
import { usePoll } from '../hooks/usePoll';
import { apiGet, type TgRegDay, type TgRegStats } from '../lib/api';
import { Empty, Kpi, Panel, PanelHead, Spinner } from '../components/ui';
import { agoSafe, fmt, uzDateSafe } from '../lib/format';
import { useToast } from '../components/Toast';

// «Foydalanuvchilar» — ilovadagi «Ro'yxatdan o'tish» shlagbaumining analitikasi.
// Manba: /api/stats?tgreg=1 (tg_registrations, migratsiya 18).
//
// Nima uchun «Qurilmalar»dan alohida: u yerda anonim qurilma tokenlari, bu yerda esa
// REAL ODAMLAR — ism, Telegram, tasdiqlangan raqam. Rassilka aynan shu ro'yxatga
// ketadi, shuning uchun voronka va "tugatmaganlar" muhim: ular ilovani o'rnatgan,
// lekin bizga yetib kelmagan foydalanuvchilar.

function niceMax(m: number): number {
  if (m <= 1) return 1;
  const pow = Math.pow(10, Math.floor(Math.log10(m)));
  return Math.ceil(m / pow) * pow || 1;
}

// 14 kunlik trend: tugatganlar (to'ldirilgan) + boshlaganlar (punktir).
// Overview'dagi TrendChart uslubi — chart-kutubxonasiz (bandl + CSP).
function RegChart({ days }: { days: TgRegDay[] }) {
  const W = 700, H = 190, padL = 34, padR = 12, padT = 14, padB = 26;
  const iw = W - padL - padR, ih = H - padT - padB;
  const top = niceMax(Math.max(1, ...days.map((d) => Math.max(d.started, d.done))));
  const x = (i: number) => padL + (days.length <= 1 ? iw / 2 : (i / (days.length - 1)) * iw);
  const y = (v: number) => padT + ih - (v / top) * ih;
  const poly = (sel: (d: TgRegDay) => number) =>
    days.map((d, i) => `${x(i).toFixed(1)},${y(sel(d)).toFixed(1)}`).join(' ');
  const baseY = (padT + ih).toFixed(1);
  const area = `${x(0).toFixed(1)},${baseY} ${poly((d) => d.done)} ${x(days.length - 1).toFixed(1)},${baseY}`;
  const grid = [0, 0.5, 1].map((f) => ({ v: Math.round(top * f), yy: padT + ih - f * ih }));
  const every = Math.max(1, Math.ceil(days.length / 7));
  return (
    <svg className="trend-svg" viewBox={`0 0 ${W} ${H}`} role="img" aria-label="14 kunlik ro'yxat trendi">
      <defs>
        <linearGradient id="ug-area" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="var(--primary)" stopOpacity="0.26" />
          <stop offset="1" stopColor="var(--primary)" stopOpacity="0" />
        </linearGradient>
      </defs>
      {grid.map((g, i) => (
        <g key={i}>
          <line x1={padL} y1={g.yy} x2={W - padR} y2={g.yy} stroke="var(--hair)" strokeWidth="1" />
          <text x={padL - 6} y={g.yy + 3} textAnchor="end" className="trend-tick">{g.v}</text>
        </g>
      ))}
      <polygon points={area} fill="url(#ug-area)" />
      <polyline points={poly((d) => d.done)} fill="none" stroke="var(--primary)" strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />
      <polyline points={poly((d) => d.started)} fill="none" stroke="#9A8D82" strokeWidth="1.5" strokeDasharray="4 3" strokeLinejoin="round" strokeLinecap="round" />
      {days.map((d, i) => (i % every === 0 || i === days.length - 1) ? (
        <text key={i} x={x(i)} y={H - 8} textAnchor="middle" className="trend-tick">{d.day.slice(5)}</text>
      ) : null)}
    </svg>
  );
}

// Voronka. Foiz HAR DOIM birinchi qadamdan (started) hisoblanadi — aks holda
// "90%" raqami qaysi bazadan olinganini o'qish qiyin bo'lardi. O'ng tomonda
// oldingi qadamdan qancha odam YO'QOLGANI ko'rsatiladi.
function Funnel({ t }: { t: TgRegStats['totals'] }) {
  const steps = [
    { label: 'Tugmani bosdi', value: t.started, color: '#9A8D82' },
    { label: 'Telegram ochdi', value: t.linked, color: '#5A8DC2' },
    { label: 'Ismini yozdi', value: t.named, color: '#DF8A18' },
    { label: 'Raqamini berdi', value: t.done, color: 'var(--primary)' },
  ];
  const base = Math.max(1, t.started);
  return (
    <div className="funnel">
      {steps.map((s, i) => {
        const pct = (s.value / base) * 100;
        const prev = i === 0 ? null : steps[i - 1].value;
        const drop = prev != null && prev > s.value ? prev - s.value : 0;
        return (
          <div className="fn-row" key={s.label}>
            <div className="fn-lbl">{s.label}</div>
            <div className="fn-bar"><i style={{ width: `${Math.max(2, pct).toFixed(1)}%`, background: s.color }} /></div>
            <div className="fn-val">
              <b>{fmt(s.value)}</b>
              <small>{pct.toFixed(0)}%{drop > 0 ? ` · −${fmt(drop)}` : ''}</small>
            </div>
          </div>
        );
      })}
    </div>
  );
}

const STEP_UZ: Record<string, string> = {
  await_name: 'Ism kutilmoqda',
  await_phone: 'Raqam kutilmoqda',
};

export default function Users() {
  const { show } = useToast();
  const [q, setQ] = useState('');
  const [exporting, setExporting] = useState(false);

  const reg = usePoll(() => apiGet<{ tgreg: TgRegStats }>('/api/stats?tgreg=1'), 30000);
  const d = reg.data?.tgreg;

  const filtered = useMemo(() => {
    const list = d?.recent ?? [];
    const needle = q.trim().toLowerCase();
    if (!needle) return list;
    return list.filter((u) =>
      (u.full_name ?? '').toLowerCase().includes(needle) ||
      (u.tg_username ?? '').toLowerCase().includes(needle) ||
      (u.phone ?? '').includes(needle));
  }, [d, q]);

  // Konversiya: tugmani bosganlarning necha foizi oxirigacha yetdi.
  const conv = d && d.totals.started > 0 ? (d.totals.done / d.totals.started) * 100 : null;

  const medianTxt = d?.median_complete_sec == null
    ? '—'
    : d.median_complete_sec < 90
      ? `${d.median_complete_sec} soniya`
      : `${Math.round(d.median_complete_sec / 60)} daqiqa`;

  // Eksport — ekrandagi (filtrlangan) ro'yxat. Niqoblangan rejimda niqoblangan
  // raqamlar tushadi: server to'liq raqamni bermagan, panel uni "tiklay" olmaydi.
  const doExport = async () => {
    if (exporting || !filtered.length) return;
    setExporting(true);
    try {
      const XLSX = await import('xlsx');
      const rows = filtered.map((u) => ({
        Ism: u.full_name ?? '',
        Telegram: u.tg_username ? `@${u.tg_username}` : '',
        Telefon: u.phone ?? '',
        "Ro'yxatdan o'tgan": u.done_at ?? '',
        Qurilma: u.has_device ? 'bor' : '—',
        Bloklagan: u.blocked ? 'ha' : '',
      }));
      const wb = XLSX.utils.book_new();
      XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(rows), 'Foydalanuvchilar');
      const t = new Date();
      const p = (n: number) => String(n).padStart(2, '0');
      XLSX.writeFile(wb, `uzguard-foydalanuvchilar-${t.getFullYear()}${p(t.getMonth() + 1)}${p(t.getDate())}.xlsx`);
      show('Excel fayl tayyor');
    } catch {
      show('Eksport amalga oshmadi');
    } finally {
      setExporting(false);
    }
  };

  if (reg.loading && !d) return <Spinner label="Yuklanmoqda…" />;
  if (reg.error && !d) return <Empty>Ma‘lumot olinmadi — keyinroq urinib ko‘ring</Empty>;

  const t = d?.totals;

  return (
    <>
      <div className="page-intro">
        <h1>Foydalanuvchilar</h1>
        <p>
          Ilovadagi «Ro‘yxatdan o‘tish» shlagbaumidan o‘tgan odamlar. Telegram boti
          ismni so‘raydi va raqamni tugma orqali oladi (raqam Telegram tomonidan
          tasdiqlanadi). E‘lonlar rassilkasi aynan shu ro‘yxatga boradi.
        </p>
      </div>

      <div className="kpis">
        <Kpi icon="👤" accent="var(--primary)" label="Ro‘yxatdan o‘tganlar"
          value={t?.done ?? 0} hint={`${fmt(t?.unique_people ?? 0)} noyob odam`} />
        <Kpi icon="📈" accent="#1A9E54" label="Bugun qo‘shildi"
          value={d?.today.done ?? 0} hint={`${fmt(d?.today.started ?? 0)} ta boshladi`} />
        <Kpi icon="🎯" accent="#DF8A18" label="Konversiya, %"
          value={Math.round(conv ?? 0)}
          hint={conv == null ? "ma'lumot yo'q" : `${fmt(t?.started ?? 0)} tadan`} />
        <Kpi icon="⏳" accent="#5A8DC2" label="Tugatmagan"
          value={t?.stuck ?? 0} hint="1 soatdan ortiq osilgan" />
        <Kpi icon="🚫" accent="#E0432F" label="Botni bloklagan"
          value={t?.blocked ?? 0} hint="rassilka ularga bormaydi" />
      </div>

      <div className="grid cols-2">
        <Panel>
          <PanelHead
            title="Ro‘yxatdan o‘tish · 14 kun" sub="Trend"
            right={
              <span className="legend">
                <i style={{ background: 'var(--primary)' }} />tugatgan
                <i style={{ background: '#9A8D82' }} />boshlagan
              </span>
            }
          />
          <div className="body-pad">
            {d && d.series.some((s) => s.started > 0 || s.done > 0)
              ? <RegChart days={d.series} />
              : <Empty>Hali ro‘yxatdan o‘tishlar yo‘q</Empty>}
          </div>
        </Panel>

        <Panel>
          <PanelHead title="Voronka" sub="Qayerda yo‘qotamiz" />
          <div className="body-pad">
            {t ? <Funnel t={t} /> : <Empty />}
            <div className="fn-foot">
              <span><small>O‘rtacha tugatish vaqti</small><b>{medianTxt}</b></span>
              <span><small>Telegram ochganlar</small><b>{fmt(t?.linked ?? 0)}</b></span>
            </div>
          </div>
        </Panel>
      </div>

      {!!d?.pending.length && (
        <Panel className="gap-top">
          <PanelHead
            title="Tugatmaganlar" sub="Telegram ochgan, oxirigacha bormagan"
            right={<span className="pill">{d.pending.length}</span>}
          />
          <div className="body-pad">
            <table>
              <thead>
                <tr><th>Ism</th><th>Telegram</th><th>Qadam</th><th>Qachon</th></tr>
              </thead>
              <tbody>
                {d.pending.map((p, i) => (
                  <tr key={`${p.chat_id}-${i}`}>
                    <td>{p.full_name || '—'}</td>
                    <td className="mono">{p.tg_username ? `@${p.tg_username}` : '—'}</td>
                    <td>{STEP_UZ[p.step] || p.step}</td>
                    <td>{agoSafe(p.linked_at)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>
      )}

      <Panel className="gap-top">
        <PanelHead
          title="Ro‘yxat"
          sub={d?.masked ? 'Raqamlar qisman yashirilgan' : 'To‘liq ma‘lumot'}
        />
        <div className="body-pad">
          <div className="list-filter">
            <input
              className="search"
              placeholder="Ism, @username yoki raqam…"
              value={q}
              onChange={(e) => setQ(e.target.value)}
            />
            <button className="btn ghost sm" onClick={doExport} disabled={exporting || !filtered.length}>
              {exporting ? <span className="spinner" /> : '⬇ Excel'}
            </button>
            <span className="lf-count">{fmt(filtered.length)} ta</span>
          </div>

          {d?.masked && (
            <div className="note">
              Telefon raqamlari qisman yashirilgan — to‘liq ko‘rinish faqat egasida.
            </div>
          )}

          {filtered.length ? (
            <table>
              <thead>
                <tr>
                  <th>Ism</th><th>Telegram</th><th>Telefon</th>
                  <th>Sana</th><th>Qurilma</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((u, i) => (
                  <tr key={`${u.chat_id}-${i}`}>
                    <td>{u.full_name || '—'}</td>
                    <td className="mono">{u.tg_username ? `@${u.tg_username}` : '—'}</td>
                    <td className="mono">{u.phone || '—'}</td>
                    <td>{uzDateSafe(u.done_at)}</td>
                    <td>
                      {u.has_device ? '✓' : '—'}
                      {u.blocked && <span className="tag"> bloklagan</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <Empty>{q ? 'Topilmadi' : 'Hali hech kim ro‘yxatdan o‘tmagan'}</Empty>
          )}
        </div>
      </Panel>
    </>
  );
}
