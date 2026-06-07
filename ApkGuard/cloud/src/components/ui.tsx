import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { fmt, riskColor, VERDICT_BG, VERDICT_FG, VERDICT_UZ } from '../lib/format';

export function Panel(
  { children, className, style }: { children: ReactNode; className?: string; style?: CSSProperties },
) {
  return <section className={'panel ' + (className || '')} style={style}>{children}</section>;
}

export function PanelHead(
  { title, sub, right }: { title: ReactNode; sub?: string; right?: ReactNode },
) {
  return (
    <div className="p-head">
      <div className="p-title">{sub && <small>{sub}</small>}<b>{title}</b></div>
      {right}
    </div>
  );
}

export function Empty({ children }: { children?: ReactNode }) {
  return <div className="empty">{children || 'Hozircha maʼlumot yo‘q'}</div>;
}

export function Spinner({ label }: { label?: string }) {
  return (
    <div className="loadbox">
      <span className="spinner" />
      {label && <span>{label}</span>}
    </div>
  );
}

// Raqam silliq sanab chiqadi (0 → target).
function useCountUp(target: number, dur = 850): number {
  const [val, setVal] = useState(target);
  const fromRef = useRef(target);
  useEffect(() => {
    const from = fromRef.current;
    const start = performance.now();
    let raf = 0;
    const tick = (now: number) => {
      const t = Math.min(1, (now - start) / dur);
      const e = 1 - Math.pow(1 - t, 3);
      setVal(from + (target - from) * e);
      if (t < 1) raf = requestAnimationFrame(tick);
      else fromRef.current = target;
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [target, dur]);
  return val;
}

export function Kpi(
  { icon, label, value, hint, accent }:
  { icon: string; label: string; value: number; hint: string; accent: string },
) {
  const v = useCountUp(value);
  return (
    <div className="kpi" style={{ ['--accent' as string]: accent }}>
      <div className="k-top"><span className="k-ico">{icon}</span></div>
      <div className="k-label">{label}</div>
      <div className="k-val">{fmt(v)}</div>
      <div className="k-delta">{hint}</div>
    </div>
  );
}

export function VerdictBadge({ verdict }: { verdict: string }) {
  const v = verdict in VERDICT_UZ ? verdict : 'error';
  return (
    <span className="vb" style={{ background: VERDICT_BG[v], color: VERDICT_FG[v] }}>
      {VERDICT_UZ[v]}
    </span>
  );
}

export function Tag({ children, kind }: { children: ReactNode; kind?: 'perm' | 'comp' }) {
  return <span className={'tag' + (kind ? ' ' + kind : '')}>{children}</span>;
}

export function LivePill({ text }: { text?: string }) {
  return (
    <span className="pill"><span className="live-dot" />{text || 'JONLI'}</span>
  );
}

// "Anatomiya skaneri" — vердиct qanday yig'ilganini ko'rsatadi: ishlagan detektorlar
// birin-ketin yonadi, xavf bali sanab chiqadi. reasons[] (telefon yuborgan sabablar +
// imzolar) o'zbekcha detektor nomlariga moslanadi — bu raqobatchidagi bitta "qora quti"
// VirusTotal chaqiruvi emas, real ko'p-detektorli dvigatel ekanini ko'rsatadi.
const DETECTORS: Array<{ test: RegExp; label: string }> = [
  { test: /zip[-_ ]?enc|zip entry|shifrlangan/i, label: 'Yashirin ZIP shifrlash (antivirus chetlash)' },
  { test: /dropper|install_packages|yuklovchi/i, label: "Dropper — yashirin APK o'rnatuvchi" },
  { test: /\bsms\b|otp/i, label: "SMS / OTP o'g'irlash" },
  { test: /accessibility|maxsus imkoniyat/i, label: "Maxsus imkoniyatlar suiiste'moli" },
  { test: /overlay|qoplama/i, label: 'Ekran qoplama (overlay) hujumi' },
  { test: /signature-mismatch|soxta imzo|qalbaki/i, label: 'Soxta raqamli imzo' },
  { test: /\bcert:|qora ro.?yxat/i, label: "Qora ro'yxatdagi imzo" },
  { test: /\bhash:|blacklist/i, label: "Ma'lum zararli fayl (hash mos keldi)" },
  { test: /\bpkg:|zararli paket/i, label: "Ma'lum zararli paket nomi" },
  { test: /impersonation|brand|brend/i, label: 'Brend nomini qalbakilashtirish' },
  { test: /homoglyph/i, label: 'Homoglyph (harf almashtirish) hujumi' },
  { test: /native/i, label: 'Shubhali native kutubxona' },
  { test: /obfusc|yashir/i, label: 'Kod yashirish (obfuskatsiya)' },
  { test: /banker|bank troyan/i, label: 'Bank troyani belgilari' },
  { test: /permission|ruxsat/i, label: "Xavfli ruxsatlar to'plami" },
];

function detectorsFrom(reasons: unknown): string[] {
  const arr = Array.isArray(reasons) ? reasons : [];
  const out: string[] = [];
  for (const r of arr) {
    if (typeof r !== 'string' || !r.trim()) continue;
    const hit = DETECTORS.find((d) => d.test.test(r));
    const label = hit ? hit.label : (r.length > 46 ? r.slice(0, 44) + '…' : r);
    if (!out.includes(label)) out.push(label);
    if (out.length >= 8) break;
  }
  return out;
}

export function ScannerAnatomy(
  { reasons, riskScore, verdict, appLabel, onClose }:
  { reasons?: unknown; riskScore?: number; verdict: string; appLabel?: string; onClose: () => void },
) {
  const dets = detectorsFrom(reasons);
  const risk = Math.max(0, Math.min(100, Math.round(riskScore || 0)));
  const [shown, setShown] = useState(0);
  useEffect(() => {
    if (shown >= dets.length) return;
    const t = window.setTimeout(() => setShown((n) => n + 1), 260);
    return () => window.clearTimeout(t);
  }, [shown, dets.length]);
  const riskUp = useCountUp(shown >= dets.length ? risk : 0, 700);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <>
      <div className="scrim" onClick={onClose} style={{ zIndex: 209 }} />
      <div className="anat-modal">
        <button className="anat-close" onClick={onClose} type="button">✕</button>
        <div className="anat-head">
          <div className="anat-title">{appLabel || 'Aniqlangan tahdid'}</div>
          <div className="anat-sub">Anatomiya skaneri · {VERDICT_UZ[verdict] || verdict}</div>
        </div>
        <div className="anat-body">
          <div className="anat-dets">
            {dets.length ? dets.map((d, i) => (
              <div key={i} className={'anat-det' + (i < shown ? ' on' : '')}>
                <i />
                <span>{d}</span>
              </div>
            )) : (
              <div className="anat-empty">Tahlil tafsilotlari yo‘q</div>
            )}
          </div>
          <div className="anat-risk">
            <small>Xavf bali</small>
            <b style={{ color: riskColor(risk) }}>{fmt(riskUp)}</b>
          </div>
        </div>
      </div>
    </>
  );
}
