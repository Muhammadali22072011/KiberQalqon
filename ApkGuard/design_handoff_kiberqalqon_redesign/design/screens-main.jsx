/* eslint-disable */
/* ============================================================
   KiberQalqon — Dashboard + APK List
   ============================================================ */

/* Real(ish) data harvested from HISOBOT_FINAL.md */
const APK_SAMPLES = [
  {
    id: "rasmlar18",
    name: "RASMLAR (18).apk",
    size: "1.6 MB",
    source: "Telegram",
    pkg: "com.lzthzvxte.xazoalzxhr",
    sha1: "A2AF260F…CB67",
    family: "Ajina.Banker",
    sev: "crit",
    label: "XAVFLI",
    perms: ["SMS_READ", "CONTACTS", "SYSTEM_ALERT_WINDOW", "PACKAGE_INSTALL"],
    when: "5 min oldin",
    color: "danger",
  },
  {
    id: "rasmlar8",
    name: "RASMLAR (8).apk",
    size: "1.4 MB",
    source: "Telegram",
    pkg: "com.oktgkst.rrcpkge",
    sha1: "C8636D1D…6FC5",
    family: "Ajina.Banker",
    sev: "crit",
    label: "XAVFLI",
    perms: ["SMS_READ", "CALL_LOG", "SYSTEM_ALERT_WINDOW"],
    when: "2 soat oldin",
    color: "danger",
  },
  {
    id: "toydan",
    name: "toydanfotolar(9.jpg).apk",
    size: "1.5 MB",
    source: "Telegram",
    pkg: "com.yzsfnie.sjsztphpis",
    sha1: "F1A8265C…D390",
    family: "Ajina.Banker",
    sev: "crit",
    label: "XAVFLI",
    perms: ["SMS_READ", "RECEIVE_SMS", "CONTACTS"],
    when: "Kecha",
    color: "danger",
  },
  {
    id: "vid23856",
    name: "VID_23856_21052026.apk",
    size: "1.5 MB",
    source: "WhatsApp",
    pkg: "com.puhfvysb.nzbftunmqq",
    sha1: "231E982E…1974",
    family: "Ajina.Banker",
    sev: "crit",
    label: "XAVFLI",
    perms: ["SMS_READ", "ACCESSIBILITY", "BIND_NOTIFICATION_LISTENER"],
    when: "Kecha",
    color: "danger",
  },
  {
    id: "video",
    name: "VIDEO.20.01.2026.mp4.apk",
    size: "2.1 MB",
    source: "ilovekkksfm.com",
    pkg: "ydbllnjd.com",
    sha1: "12AD86B8…8476",
    family: "RoundRift",
    sev: "crit",
    label: "DROPPER",
    perms: ["INSTALL_PACKAGES", "WRITE_EXTERNAL_STORAGE", "SYSTEM_ALERT_WINDOW"],
    when: "Bugun 09:14",
    color: "danger",
  },
  {
    id: "telegram",
    name: "Telegram.apk",
    size: "73.2 MB",
    source: "Play Market",
    pkg: "org.telegram.messenger",
    sha1: "1A2B3C4D…BEEF",
    family: null,
    sev: "low",
    label: "XAVFSIZ",
    perms: ["INTERNET", "STORAGE", "CAMERA"],
    when: "1 hafta oldin",
    color: "safe",
  },
  {
    id: "instagram",
    name: "Instagram.apk",
    size: "65.0 MB",
    source: "Play Market",
    pkg: "com.instagram.android",
    sha1: "5E6F7A8B…F00D",
    family: null,
    sev: "low",
    label: "XAVFSIZ",
    perms: ["INTERNET", "CAMERA", "STORAGE"],
    when: "1 hafta oldin",
    color: "safe",
  },
  {
    id: "uztaxi",
    name: "MyTaxi.apk",
    size: "42.1 MB",
    source: "Play Market",
    pkg: "uz.mytaxi.app",
    sha1: "9F8E7D6C…CAFE",
    family: null,
    sev: "low",
    label: "XAVFSIZ",
    perms: ["LOCATION", "INTERNET"],
    when: "3 kun oldin",
    color: "safe",
  },
];

const Dashboard = ({ goto, theme, lang }) => {
  return (
    <div className="screen-pad fade-in" style={{ paddingTop: 8 }}>
      {/* Top bar */}
      <div className="between" style={{ marginBottom: 16 }}>
        <div className="row" style={{ gap: 10 }}>
          <LogoMark size={32}/>
          <div>
            <div style={{ font: "700 16px/1 var(--font-display)", color: "var(--ink)" }}>KiberQalqon</div>
            <div style={{ font: "500 10px/1 var(--font-mono)", color: "var(--ink-3)", marginTop: 3, letterSpacing: "0.1em" }}>HIMOYA · FAOL</div>
          </div>
        </div>
        <div className="row" style={{ gap: 8 }}>
          <button className="icon-btn" aria-label="lang">
            <span style={{ font: "700 10px/1 var(--font-mono)" }}>UZ</span>
          </button>
          <button className="icon-btn" aria-label="notifications">
            <I.bell size={18}/>
          </button>
        </div>
      </div>

      {/* Hero card — protection status */}
      <div className="card pattern-bg" style={{
        position: "relative",
        padding: "22px 18px 18px",
        background: "linear-gradient(160deg, var(--bg-elev) 0%, var(--primary-soft) 130%)",
        borderColor: "color-mix(in oklch, var(--primary) 18%, var(--hairline))",
        overflow: "hidden",
      }}>
        {/* ornament overlay */}
        <div style={{
          position: "absolute", right: -30, top: -30,
          opacity: 0.10, color: "var(--primary)",
        }}>
          <svg width="200" height="200" viewBox="0 0 200 200">
            <g fill="none" stroke="currentColor" strokeWidth="1.2">
              <path d="M100 20 L120 80 L180 100 L120 120 L100 180 L80 120 L20 100 L80 80 Z"/>
              <path d="M100 40 L115 85 L160 100 L115 115 L100 160 L85 115 L40 100 L85 85 Z"/>
              <circle cx="100" cy="100" r="6"/>
            </g>
          </svg>
        </div>

        <div className="between">
          <div>
            <div className="h-eyebrow">SIZNING TELEFONINGIZ</div>
            <div style={{
              font: "700 22px/1.1 var(--font-display)",
              letterSpacing: "-0.015em",
              marginTop: 6, color: "var(--ink)",
            }}>Himoyalangan</div>
            <div style={{
              font: "500 13px/1.4 var(--font-body)",
              color: "var(--ink-2)",
              marginTop: 4, maxWidth: 200,
            }}>So'nggi tekshiruv 12 daqiqa oldin</div>
          </div>
          <ProtectionMeter value={92} size={140} label="Faol"/>
        </div>

        <div style={{ display: "flex", gap: 8, marginTop: 14 }}>
          <button className="btn primary" style={{ flex: 1 }} onClick={() => goto("apk")}>
            <I.scan size={18}/> Hozir tekshirish
          </button>
          <button className="btn ghost" style={{ width: 52, padding: 0 }} aria-label="refresh">
            <I.refresh size={18}/>
          </button>
        </div>
      </div>

      {/* Stats row */}
      <div style={{
        display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: 10,
        marginTop: 14,
      }}>
        {[
          { v: 247, l: "Tekshirildi", c: "primary"},
          { v: 5,   l: "Bloklandi",  c: "danger"},
          { v: 12,  l: "Karantin",   c: "warn"},
        ].map((s, i) => (
          <div key={i} className="card" style={{ padding: "12px 12px" }}>
            <div style={{
              font: "700 24px/1 var(--font-display)",
              color: s.c === "primary" ? "var(--ink)" : `var(--${s.c})`,
              letterSpacing: "-0.02em",
            }}>{s.v}</div>
            <div style={{
              font: "500 11px/1.2 var(--font-mono)",
              color: "var(--ink-3)",
              textTransform: "uppercase",
              letterSpacing: "0.08em",
              marginTop: 4,
            }}>{s.l}</div>
          </div>
        ))}
      </div>

      {/* Latest threats */}
      <div className="head-row" style={{ marginTop: 22, marginBottom: 10 }}>
        <div>
          <div className="h-eyebrow">SO'NGGI XAVFLAR</div>
          <div style={{ font: "700 18px/1.2 var(--font-display)", color: "var(--ink)", marginTop: 4 }}>
            Bugungi topilmalar
          </div>
        </div>
        <button className="btn" style={{
          background: "transparent", color: "var(--primary)",
          fontSize: 13, height: 36, padding: "0 8px",
        }} onClick={() => goto("apk")}>
          Hammasi <I.chevR size={14}/>
        </button>
      </div>

      <div className="card">
        {APK_SAMPLES.filter(a => a.color === "danger").slice(0, 3).map((a, i, arr) => (
          <div key={a.id} className="li-row" onClick={() => goto("result", a.id)}
            style={{ borderBottom: i < arr.length - 1 ? "1px solid var(--hairline)" : "none" }}>
            <div className="ico" style={{
              background: "var(--danger-bg)", color: "var(--danger-ink)",
            }}>
              <I.bug size={22}/>
            </div>
            <div className="meta">
              <div className="h" style={{
                whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis"
              }}>{a.name}</div>
              <div className="s">{a.family} · {a.source} · {a.when}</div>
            </div>
            <div className={`sev ${a.sev}`}><span className="dot"/>{a.label}</div>
          </div>
        ))}
      </div>

      {/* Anti-analysis chip row */}
      <div style={{ marginTop: 22 }}>
        <div className="h-eyebrow">AKTIV MODULLAR</div>
        <div style={{ font: "700 18px/1.2 var(--font-display)", color: "var(--ink)", marginTop: 4, marginBottom: 12 }}>
          Himoya qatlamlari
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(2, 1fr)", gap: 10 }}>
          {[
            { i: <I.zap/>,    n: "FileObserver",  s: "Yuklab olishlar nazoratda", on: true },
            { i: <I.lock/>,   n: "ZIP-evasion",   s: "GP-flag aldovini ochadi",   on: true },
            { i: <I.bell/>,   n: "Phish bloker",  s: "OTP bildirishnomalari",     on: true },
            { i: <I.globe/>,  n: "C2 qora ro'yhat", s: "elrxzx.com va boshqalar", on: true },
          ].map((m, i) => (
            <div key={i} className="card" style={{
              padding: "12px 12px",
              borderColor: m.on ? "color-mix(in oklch, var(--primary) 22%, var(--hairline))" : "var(--hairline)",
            }}>
              <div className="row" style={{ justifyContent: "space-between" }}>
                <div style={{
                  width: 32, height: 32, borderRadius: 10,
                  background: "var(--primary-soft)", color: "var(--primary)",
                  display: "grid", placeItems: "center",
                }}>{m.i}</div>
                <div style={{
                  width: 8, height: 8, borderRadius: "50%",
                  background: m.on ? "var(--safe)" : "var(--ink-3)",
                  boxShadow: m.on ? "0 0 0 4px color-mix(in oklch, var(--safe) 22%, transparent)" : "none",
                }}/>
              </div>
              <div style={{
                font: "700 13px/1.15 var(--font-display)",
                color: "var(--ink)",
                marginTop: 10,
              }}>{m.n}</div>
              <div style={{
                font: "400 11px/1.35 var(--font-body)",
                color: "var(--ink-3)",
                marginTop: 4,
              }}>{m.s}</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};

/* ── APK list screen ─────────────────────────────────────── */
const ApkList = ({ goto }) => {
  const [filter, setFilter] = React.useState("all");
  const filters = [
    { id: "all",   l: "Hammasi",  n: APK_SAMPLES.length },
    { id: "danger",l: "Xavfli",   n: APK_SAMPLES.filter(a=>a.color==="danger").length },
    { id: "safe",  l: "Xavfsiz",  n: APK_SAMPLES.filter(a=>a.color==="safe").length },
  ];
  const visible = APK_SAMPLES.filter(a =>
    filter === "all" ? true : a.color === filter
  );
  return (
    <div className="screen-pad fade-in" style={{ paddingTop: 6 }}>
      <div className="between" style={{ marginBottom: 14 }}>
        <div>
          <div className="h-eyebrow">SKANER</div>
          <div className="h-title" style={{ marginTop: 6 }}>APK fayllar</div>
          <div className="h-sub">{APK_SAMPLES.length} ta fayl tekshirildi</div>
        </div>
        <button className="icon-btn" aria-label="upload" onClick={() => goto("alert")}>
          <I.upload size={18}/>
        </button>
      </div>

      {/* Filter chips */}
      <div style={{ display: "flex", gap: 8, marginBottom: 14, overflowX: "auto", paddingBottom: 4 }}>
        {filters.map(f => (
          <button key={f.id} onClick={() => setFilter(f.id)}
            className="chip"
            style={{
              height: 34, padding: "0 14px", fontSize: 12,
              border: filter === f.id ? "1px solid var(--primary)" : "1px solid var(--hairline-strong)",
              background: filter === f.id ? "var(--primary-soft)" : "transparent",
              color: filter === f.id ? "var(--primary)" : "var(--ink-2)",
              textTransform: "none",
              fontFamily: "var(--font-display)",
              letterSpacing: 0,
              cursor: "pointer",
              flexShrink: 0,
            }}>
            {f.l}
            <span style={{
              padding: "1px 6px", borderRadius: 6,
              background: filter === f.id ? "var(--primary)" : "var(--bg-sunken)",
              color: filter === f.id ? "var(--on-primary)" : "var(--ink-3)",
              fontFamily: "var(--font-mono)", fontWeight: 700,
              fontSize: 10,
            }}>{f.n}</span>
          </button>
        ))}
      </div>

      {/* List */}
      <div className="card">
        {visible.map((a, i, arr) => (
          <div key={a.id} className="li-row" onClick={() => goto("result", a.id)}
            style={{ borderBottom: i < arr.length - 1 ? "1px solid var(--hairline)" : "none" }}>
            <div className="ico" style={{
              background: a.color === "danger" ? "var(--danger-bg)" :
                          a.color === "warn"   ? "var(--warn-bg)"   :
                                                 "var(--safe-bg)",
              color: a.color === "danger" ? "var(--danger-ink)" :
                     a.color === "warn"   ? "var(--warn-ink)"   :
                                            "var(--safe-ink)",
            }}>
              {a.color === "danger" ? <I.bug size={22}/> : <I.check size={22}/>}
            </div>
            <div className="meta">
              <div className="h" style={{
                whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis"
              }}>{a.name}</div>
              <div className="s mono">{a.pkg}</div>
              <div className="row" style={{ marginTop: 6, gap: 6 }}>
                <span className={`sev ${a.sev}`}><span className="dot"/>{a.label}</span>
                <span style={{
                  font: "500 10.5px/1 var(--font-mono)",
                  color: "var(--ink-3)",
                  letterSpacing: "0.06em",
                }}>{a.size} · {a.source}</span>
              </div>
            </div>
            <I.chevR size={16}/>
          </div>
        ))}
      </div>

      <div style={{ marginTop: 14 }}>
        <button className="btn ghost block" onClick={() => goto("alert")}>
          <I.scan size={18}/> Hammasi qayta tekshirilsin
        </button>
      </div>
    </div>
  );
};

Object.assign(window, { Dashboard, ApkList, APK_SAMPLES });
