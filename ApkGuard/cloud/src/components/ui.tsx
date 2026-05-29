import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { fmt, VERDICT_BG, VERDICT_FG, VERDICT_UZ } from '../lib/format';

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
