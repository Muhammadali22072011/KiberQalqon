/* ============================================================
   KiberQalqon — Iconlar va umumiy komponentlar
   Sodda chiziqli iconlar (Lucide uslubida).
   ============================================================ */

const Ic = ({ d, size = 24, sw = 2, fill = "none", style }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill={fill}
       stroke="currentColor" strokeWidth={sw} strokeLinecap="round"
       strokeLinejoin="round" style={style}>
    {Array.isArray(d) ? d.map((p, i) => <path key={i} d={p} />) : <path d={d} />}
  </svg>
);

const I = {
  shield: (p) => <Ic {...p} d="M12 2 4 5v6c0 5 3.4 8.5 8 10 4.6-1.5 8-5 8-10V5l-8-3Z" />,
  shieldCheck: (p) => <Ic {...p} d={["M12 2 4 5v6c0 5 3.4 8.5 8 10 4.6-1.5 8-5 8-10V5l-8-3Z","M9 12l2 2 4-4"]} />,
  shieldAlert: (p) => <Ic {...p} d={["M12 2 4 5v6c0 5 3.4 8.5 8 10 4.6-1.5 8-5 8-10V5l-8-3Z","M12 8v4","M12 16h.01"]} />,
  check: (p) => <Ic {...p} d="M5 12l5 5L20 6" />,
  checkCircle: (p) => <Ic {...p} d={["M22 11.1V12a10 10 0 1 1-5.9-9.1","M22 4 12 14.5l-3-3"]} />,
  x: (p) => <Ic {...p} d="M6 6l12 12M18 6 6 18" />,
  xCircle: (p) => <Ic {...p} d={["M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20Z","M15 9l-6 6M9 9l6 6"]} />,
  alert: (p) => <Ic {...p} d={["M10.3 3.8 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.8a2 2 0 0 0-3.4 0Z","M12 9v4","M12 17h.01"]} />,
  scan: (p) => <Ic {...p} d={["M3 7V5a2 2 0 0 1 2-2h2","M17 3h2a2 2 0 0 1 2 2v2","M21 17v2a2 2 0 0 1-2 2h-2","M7 21H5a2 2 0 0 1-2-2v-2","M3 12h18"]} />,
  search: (p) => <Ic {...p} d={["M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16Z","M21 21l-4.3-4.3"]} />,
  bell: (p) => <Ic {...p} d={["M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9","M13.7 21a2 2 0 0 1-3.4 0"]} />,
  gear: (p) => <Ic {...p} d={["M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z","M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-2.7 1.1V21a2 2 0 0 1-4 0v-.1A1.6 1.6 0 0 0 7 19.4a1.6 1.6 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0-1.1-2.7H1a2 2 0 0 1 0-4h.1A1.6 1.6 0 0 0 2.6 7a1.6 1.6 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 1.8.3H7a1.6 1.6 0 0 0 1-1.5V1a2 2 0 0 1 4 0v.1a1.6 1.6 0 0 0 1 1.5 1.6 1.6 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0-.3 1.8V7a1.6 1.6 0 0 0 1.5 1H23a2 2 0 0 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1Z"]} />,
  chart: (p) => <Ic {...p} d={["M3 3v18h18","M7 15v3","M12 9v9","M17 5v13"]} />,
  trash: (p) => <Ic {...p} d={["M3 6h18","M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2","M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"]} />,
  refresh: (p) => <Ic {...p} d={["M3 12a9 9 0 0 1 15-6.7L21 8","M21 3v5h-5","M21 12a9 9 0 0 1-15 6.7L3 16","M3 21v-5h5"]} />,
  chevron: (p) => <Ic {...p} d="M9 6l6 6-6 6" />,
  chevDown: (p) => <Ic {...p} d="M6 9l6 6 6-6" />,
  back: (p) => <Ic {...p} d={["M19 12H5","M12 19l-7-7 7-7"]} />,
  phone: (p) => <Ic {...p} d={["M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2Z","M11 19h2"]} />,
  download: (p) => <Ic {...p} d={["M12 3v12","M7 10l5 5 5-5","M5 21h14"]} />,
  file: (p) => <Ic {...p} d={["M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6Z","M14 2v6h6"]} />,
  folder: (p) => <Ic {...p} d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z" />,
  lock: (p) => <Ic {...p} d={["M5 11h14a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1Z","M8 11V7a4 4 0 0 1 8 0v4"]} />,
  key: (p) => <Ic {...p} d={["M15 9a3 3 0 1 0-3 3","M21 3l-7.5 7.5","M16 6l3 3"]} />,
  eye: (p) => <Ic {...p} d={["M2 12s4-7 10-7 10 7 10 7-4 7-10 7-10-7-10-7Z","M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z"]} />,
  message: (p) => <Ic {...p} d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v10Z" />,
  globe: (p) => <Ic {...p} d={["M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20Z","M2 12h20","M12 2a15 15 0 0 1 0 20 15 15 0 0 1 0-20Z"]} />,
  clock: (p) => <Ic {...p} d={["M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20Z","M12 7v5l3 2"]} />,
  sun: (p) => <Ic {...p} d={["M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10Z","M12 1v2","M12 21v2","M4.2 4.2l1.4 1.4","M18.4 18.4l1.4 1.4","M1 12h2","M21 12h2","M4.2 19.8l1.4-1.4","M18.4 5.6l1.4-1.4"]} />,
  moon: (p) => <Ic {...p} d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" />,
  heart: (p) => <Ic {...p} d="M12 21s-8-4.7-8-11a4.5 4.5 0 0 1 8-2.8A4.5 4.5 0 0 1 20 10c0 6.3-8 11-8 11Z" />,
  star: (p) => <Ic {...p} d="M12 3l2.6 5.6 6 .8-4.4 4.2 1.1 6L12 17l-5.3 2.6 1.1-6L3.4 9.4l6-.8L12 3Z" />,
  card: (p) => <Ic {...p} d={["M3 6h18a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1Z","M2 10h20"]} />,
  wifi: (p) => <Ic {...p} d={["M5 13a10 10 0 0 1 14 0","M8.5 16.5a5 5 0 0 1 7 0","M12 20h.01","M2 9a15 15 0 0 1 20 0"]} />,
  layers: (p) => <Ic {...p} d={["M12 2 2 7l10 5 10-5-10-5Z","M2 12l10 5 10-5","M2 17l10 5 10-5"]} />,
  user: (p) => <Ic {...p} d={["M20 21a8 8 0 1 0-16 0","M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z"]} />,
  help: (p) => <Ic {...p} d={["M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20Z","M9.1 9a3 3 0 0 1 5.8 1c0 2-3 3-3 3","M12 17h.01"]} />,
};

/* ── ANOR QALQON logo (real pomegranate-shield PNG) ── */
const Logo = ({ size = 64, radius, style }) => (
  <img src={window.LOGO_SRC || "logo-anor.png"} width={size} height={size} alt="Anor Qalqon"
    style={{ display: "block", borderRadius: radius, objectFit: "contain", ...style }} />
);
/* logo on a white disc — for use on colored backgrounds */
const LogoDisc = ({ size = 96, pad = 0.16 }) => (
  <div style={{ width: size, height: size, borderRadius: "50%", background: "#fff",
    display: "grid", placeItems: "center", boxShadow: "0 10px 30px rgba(0,0,0,.18)" }}>
    <Logo size={Math.round(size * (1 - pad))} />
  </div>
);

/* national 8-point star tile (subtle ornament) */
const STAR_TILE = encodeURIComponent(
  `<svg xmlns='http://www.w3.org/2000/svg' width='78' height='78' viewBox='0 0 78 78'>
    <g fill='none' stroke='%23000' stroke-width='1.3'>
      <path d='M39 8 L46 32 L70 39 L46 46 L39 70 L32 46 L8 39 L32 32 Z'/>
      <path d='M39 18 L44 34 L60 39 L44 44 L39 60 L34 44 L18 39 L34 34 Z'/>
    </g>
  </svg>`
).replace(/%23000/g, "%23a23a2c");

/* ── Protection ring (big status meter) ──────────────────── */
const Ring = ({ value = 100, size = 150, stroke = 13, color = "var(--primary)", track = "var(--hairline)", children }) => {
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const off = c * (1 - value / 100);
  return (
    <div style={{ position: "relative", width: size, height: size }}>
      <svg width={size} height={size} style={{ transform: "rotate(-90deg)" }}>
        <circle cx={size/2} cy={size/2} r={r} fill="none" stroke={track} strokeWidth={stroke} />
        <circle cx={size/2} cy={size/2} r={r} fill="none" stroke={color} strokeWidth={stroke}
          strokeLinecap="round" strokeDasharray={c} strokeDashoffset={off}
          style={{ transition: "stroke-dashoffset 1.2s var(--ease)" }} />
      </svg>
      <div style={{ position: "absolute", inset: 0, display: "grid", placeItems: "center" }}>
        {children}
      </div>
    </div>
  );
};

Object.assign(window, { Ic, I, Logo, LogoDisc, STAR_TILE, Ring });
