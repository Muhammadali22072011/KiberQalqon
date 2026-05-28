/* eslint-disable */
/* ============================================================
   KiberQalqon — Ornaments, icons, and the Uzbek 8-point star
   pattern (used as subtle background on hero areas).
   ============================================================ */

/* The pattern: 8-point star (rub el hizb) over hex grid.
   We render a single SVG and let CSS tile it. */
const StarTile = ({ size = 80, color = "currentColor", opacity = 0.18 }) => (
  <svg width={size} height={size} viewBox="0 0 80 80" xmlns="http://www.w3.org/2000/svg">
    <g fill="none" stroke={color} strokeWidth="1.2" opacity={opacity}>
      {/* 8-point star centered at 40,40 */}
      <path d="M40 8 L48 32 L72 40 L48 48 L40 72 L32 48 L8 40 L32 32 Z" />
      <path d="M40 18 L46 34 L62 40 L46 46 L40 62 L34 46 L18 40 L34 34 Z" />
      {/* corner accents */}
      <circle cx="0" cy="0" r="2.5" fill={color} stroke="none" />
      <circle cx="80" cy="0" r="2.5" fill={color} stroke="none" />
      <circle cx="0" cy="80" r="2.5" fill={color} stroke="none" />
      <circle cx="80" cy="80" r="2.5" fill={color} stroke="none" />
    </g>
  </svg>
);

/* Big shield with star inside — main brand mark */
const ShieldMark = ({ size = 80, glow = false }) => (
  <svg width={size} height={size} viewBox="0 0 100 100" xmlns="http://www.w3.org/2000/svg">
    <defs>
      <linearGradient id="shield-grad" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0%" stopColor="var(--primary)" />
        <stop offset="100%" stopColor="var(--primary-2)" />
      </linearGradient>
      {glow && (
        <filter id="shield-glow" x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur stdDeviation="3" />
        </filter>
      )}
    </defs>
    {glow && (
      <path
        d="M50 6 L86 18 V46 C86 70 70 86 50 94 C30 86 14 70 14 46 V18 Z"
        fill="url(#shield-grad)"
        filter="url(#shield-glow)"
        opacity="0.5"
      />
    )}
    <path
      d="M50 6 L86 18 V46 C86 70 70 86 50 94 C30 86 14 70 14 46 V18 Z"
      fill="url(#shield-grad)"
    />
    {/* 8-point star inside */}
    <g transform="translate(50 50)" fill="rgba(255,255,255,0.95)" stroke="none">
      <path d="M0 -28 L8 -8 L28 0 L8 8 L0 28 L-8 8 L-28 0 L-8 -8 Z" />
    </g>
    <circle cx="50" cy="50" r="4" fill="var(--primary)" />
  </svg>
);

/* Compact logo for app bars */
const LogoMark = ({ size = 28 }) => (
  <svg width={size} height={size} viewBox="0 0 32 32">
    <path
      d="M16 2 L28 6 V15 C28 23 23 28.5 16 31 C9 28.5 4 23 4 15 V6 Z"
      fill="var(--primary)"
    />
    <path
      d="M16 11 L18.5 14.5 L22 16 L18.5 17.5 L16 21 L13.5 17.5 L10 16 L13.5 14.5 Z"
      fill="#fff"
    />
  </svg>
);

/* Stroke icons (24x24 base) — Lucide-ish style */
const I = {
  shield: (p) => (
    <svg width={p?.size||22} height={p?.size||22} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 2 L20 5 V11 C20 16 16.5 20.5 12 22 C7.5 20.5 4 16 4 11 V5 Z"/>
      <path d="M9 12 l2 2 4-4"/>
    </svg>
  ),
  scan: (p) => (
    <svg width={p?.size||22} height={p?.size||22} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 7 V5 a2 2 0 0 1 2 -2 h2"/>
      <path d="M17 3 h2 a2 2 0 0 1 2 2 v2"/>
      <path d="M21 17 v2 a2 2 0 0 1 -2 2 h-2"/>
      <path d="M7 21 h-2 a2 2 0 0 1 -2 -2 v-2"/>
      <path d="M7 12 h10"/>
    </svg>
  ),
  list: (p) => (
    <svg width={p?.size||22} height={p?.size||22} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M8 6 h13"/><path d="M8 12 h13"/><path d="M8 18 h13"/>
      <circle cx="4" cy="6" r="1"/><circle cx="4" cy="12" r="1"/><circle cx="4" cy="18" r="1"/>
    </svg>
  ),
  settings: (p) => (
    <svg width={p?.size||22} height={p?.size||22} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="3"/>
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>
    </svg>
  ),
  alert: (p) => (
    <svg width={p?.size||22} height={p?.size||22} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
      <line x1="12" y1="9" x2="12" y2="13"/>
      <line x1="12" y1="17" x2="12.01" y2="17"/>
    </svg>
  ),
  check: (p) => (
    <svg width={p?.size||22} height={p?.size||22} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
      <polyline points="20 6 9 17 4 12"/>
    </svg>
  ),
  x: (p) => (
    <svg width={p?.size||22} height={p?.size||22} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
    </svg>
  ),
  chevR: (p) => (
    <svg width={p?.size||18} height={p?.size||18} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polyline points="9 18 15 12 9 6"/>
    </svg>
  ),
  chevL: (p) => (
    <svg width={p?.size||18} height={p?.size||18} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polyline points="15 18 9 12 15 6"/>
    </svg>
  ),
  trash: (p) => (
    <svg width={p?.size||18} height={p?.size||18} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polyline points="3 6 5 6 21 6"/>
      <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/>
      <path d="M10 11v6"/><path d="M14 11v6"/>
    </svg>
  ),
  download: (p) => (
    <svg width={p?.size||18} height={p?.size||18} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
      <polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>
    </svg>
  ),
  globe: (p) => (
    <svg width={p?.size||18} height={p?.size||18} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/>
      <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/>
    </svg>
  ),
  bell: (p) => (
    <svg width={p?.size||18} height={p?.size||18} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/>
      <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"/>
    </svg>
  ),
  lock: (p) => (
    <svg width={p?.size||18} height={p?.size||18} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="3" y="11" width="18" height="11" rx="2"/>
      <path d="M7 11V7a5 5 0 0 1 10 0v4"/>
    </svg>
  ),
  server: (p) => (
    <svg width={p?.size||18} height={p?.size||18} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="2" y="3" width="20" height="6" rx="1"/>
      <rect x="2" y="14" width="20" height="6" rx="1"/>
      <line x1="6" y1="6" x2="6.01" y2="6"/><line x1="6" y1="17" x2="6.01" y2="17"/>
    </svg>
  ),
  moon: (p) => (
    <svg width={p?.size||18} height={p?.size||18} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>
    </svg>
  ),
  sun: (p) => (
    <svg width={p?.size||18} height={p?.size||18} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="4"/>
      <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/>
    </svg>
  ),
  bug: (p) => (
    <svg width={p?.size||22} height={p?.size||22} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="8" y="6" width="8" height="14" rx="4"/>
      <path d="M19 7l-3 2M5 7l3 2M19 13h-3M5 13h3M19 19l-3-2M5 19l3-2M12 2v4"/>
    </svg>
  ),
  upload: (p) => (
    <svg width={p?.size||18} height={p?.size||18} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
      <polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/>
    </svg>
  ),
  refresh: (p) => (
    <svg width={p?.size||18} height={p?.size||18} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/>
      <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>
    </svg>
  ),
  zap: (p) => (
    <svg width={p?.size||18} height={p?.size||18} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
    </svg>
  ),
  file: (p) => (
    <svg width={p?.size||22} height={p?.size||22} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
      <polyline points="14 2 14 8 20 8"/>
    </svg>
  ),
  info: (p) => (
    <svg width={p?.size||18} height={p?.size||18} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/>
    </svg>
  ),
  stats: (p) => (
    <svg width={p?.size||22} height={p?.size||22} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <line x1="18" y1="20" x2="18" y2="10"/>
      <line x1="12" y1="20" x2="12" y2="4"/>
      <line x1="6" y1="20" x2="6" y2="14"/>
    </svg>
  ),
  wifi: (p) => (
    <svg width={p?.size||14} height={p?.size||14} viewBox="0 0 24 24" fill="currentColor">
      <path d="M12 18a2 2 0 1 1 0 4 2 2 0 0 1 0-4zm0-5c2.4 0 4.6 1 6.2 2.6l-1.5 1.5A6.5 6.5 0 0 0 12 15c-1.9 0-3.6.8-4.7 2.1L5.8 15.6A8.7 8.7 0 0 1 12 13zm0-5a13 13 0 0 1 9.2 3.8l-1.5 1.5a11 11 0 0 0-15.4 0L2.8 11.8A13 13 0 0 1 12 8zm0-5c4.6 0 8.7 1.8 11.7 4.8l-1.5 1.5A14.5 14.5 0 0 0 12 5C8 5 4.5 6.4 1.8 8.8L.3 7.3A16.5 16.5 0 0 1 12 3z"/>
    </svg>
  ),
  battery: (p) => (
    <svg width={p?.size||20} height={p?.size||14} viewBox="0 0 24 14" fill="none" stroke="currentColor" strokeWidth="1.6">
      <rect x="1" y="1" width="20" height="12" rx="2"/>
      <rect x="3" y="3" width="14" height="8" rx="1" fill="currentColor" stroke="none"/>
      <line x1="22.5" y1="5" x2="22.5" y2="9" strokeWidth="2.5"/>
    </svg>
  ),
};

/* ── Pulsing scan visual for the dashboard hero ─────────── */
const ProtectionMeter = ({ value = 92, size = 220, label = "Himoyalangan" }) => {
  const r = size / 2 - 18;
  const c = 2 * Math.PI * r;
  const off = c * (1 - value / 100);
  return (
    <div style={{ position: "relative", width: size, height: size }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
        <defs>
          <linearGradient id="meter-grad" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor="var(--primary)" />
            <stop offset="100%" stopColor="var(--accent)" />
          </linearGradient>
        </defs>
        {/* track */}
        <circle cx={size/2} cy={size/2} r={r}
          fill="none" stroke="var(--hairline)" strokeWidth="10" opacity="0.5" />
        {/* progress */}
        <circle cx={size/2} cy={size/2} r={r}
          fill="none" stroke="url(#meter-grad)" strokeWidth="10"
          strokeLinecap="round"
          strokeDasharray={c} strokeDashoffset={off}
          className="shield-arc"
          transform={`rotate(-90 ${size/2} ${size/2})`}
        />
        {/* tick marks */}
        {Array.from({length: 36}).map((_, i) => {
          const a = (i / 36) * Math.PI * 2 - Math.PI/2;
          const r1 = r - 14, r2 = r - 18;
          const x1 = size/2 + Math.cos(a)*r1, y1 = size/2 + Math.sin(a)*r1;
          const x2 = size/2 + Math.cos(a)*r2, y2 = size/2 + Math.sin(a)*r2;
          const within = (i / 36) <= (value / 100);
          return <line key={i} x1={x1} y1={y1} x2={x2} y2={y2}
            stroke={within ? "var(--primary)" : "var(--hairline)"}
            strokeWidth="1.5" opacity={within ? 0.5 : 0.4} />;
        })}
      </svg>
      <div style={{
        position: "absolute", inset: 0,
        display: "flex", flexDirection: "column",
        alignItems: "center", justifyContent: "center",
      }}>
        <div style={{ width: 60, height: 60, marginBottom: 6 }}>
          <ShieldMark size={60} />
        </div>
        <div style={{
          font: "700 36px/1 var(--font-display)",
          letterSpacing: "-0.02em",
          color: "var(--ink)"
        }}>
          {value}<span style={{ fontSize: 18, color: "var(--ink-3)" }}>%</span>
        </div>
        <div style={{
          font: "600 11px/1 var(--font-mono)",
          letterSpacing: "0.12em",
          textTransform: "uppercase",
          color: "var(--primary)",
          marginTop: 6,
        }}>{label}</div>
      </div>
    </div>
  );
};

Object.assign(window, {
  StarTile, ShieldMark, LogoMark, I, ProtectionMeter
});
