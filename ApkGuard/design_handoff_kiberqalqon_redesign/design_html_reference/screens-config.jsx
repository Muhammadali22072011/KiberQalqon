/* eslint-disable */
/* ============================================================
   KiberQalqon — Settings + Statistics
   ============================================================ */

const Toggle = ({ on, onChange }) => (
  <button
    onClick={() => onChange(!on)}
    aria-pressed={on}
    style={{
      width: 44, height: 26, borderRadius: 999,
      background: on ? "var(--primary)" : "var(--hairline-strong)",
      border: "none", cursor: "pointer",
      position: "relative",
      transition: "background .18s ease",
      flexShrink: 0,
    }}>
    <span style={{
      position: "absolute", top: 3, left: on ? 21 : 3,
      width: 20, height: 20, borderRadius: "50%",
      background: "#fff",
      transition: "left .18s cubic-bezier(.2,.7,.2,1)",
      boxShadow: "0 1px 3px rgba(0,0,0,0.25)",
    }}/>
  </button>
);

const SettingsScreen = ({ goto, tweaks, setTweak }) => {
  const [auto, setAuto] = React.useState(true);
  const [autoDelete, setAutoDelete] = React.useState(true);
  const [serverUpload, setServerUpload] = React.useState(false);
  const [phishBlock, setPhishBlock] = React.useState(true);
  const [bg, setBg] = React.useState(true);

  const row = (icon, title, sub, control) => (
    <div className="li-row" style={{ cursor: "default", borderBottom: "1px solid var(--hairline)" }}>
      <div className="ico" style={{ background: "var(--primary-soft)", color: "var(--primary)" }}>{icon}</div>
      <div className="meta">
        <div className="h">{title}</div>
        {sub && <div style={{
          font: "400 11.5px/1.4 var(--font-body)", color: "var(--ink-2)",
          marginTop: 3, whiteSpace: "normal",
        }}>{sub}</div>}
      </div>
      {control}
    </div>
  );

  return (
    <div className="screen-pad fade-in" style={{ paddingTop: 6 }}>
      <div className="head-row">
        <div>
          <div className="h-eyebrow">SOZLAMALAR</div>
          <div className="h-title" style={{ marginTop: 6 }}>Boshqaruv</div>
          <div className="h-sub">Himoya darajasini moslashtiring</div>
        </div>
      </div>

      {/* Profile / status card */}
      <div className="card" style={{
        padding: "14px 14px",
        background: "linear-gradient(160deg, var(--bg-elev) 0%, var(--primary-soft) 130%)",
        borderColor: "color-mix(in oklch, var(--primary) 20%, var(--hairline))",
      }}>
        <div className="between">
          <div className="row" style={{ gap: 12 }}>
            <div style={{
              width: 48, height: 48, borderRadius: 14,
              background: "var(--primary)", display: "grid", placeItems: "center",
            }}>
              <ShieldMark size={28}/>
            </div>
            <div>
              <div style={{ font: "700 15px/1.1 var(--font-display)", color: "var(--ink)" }}>KiberQalqon Pro</div>
              <div style={{
                font: "500 11px/1 var(--font-mono)", color: "var(--ink-3)",
                marginTop: 4, letterSpacing: "0.1em",
              }}>VERSIYA 7.5 · TASHKENT</div>
            </div>
          </div>
          <span className="chip safe"><I.check size={12}/> FAOL</span>
        </div>
      </div>

      {/* Sections */}
      <div style={{ marginTop: 18 }}>
        <div className="h-eyebrow" style={{ marginBottom: 8 }}>HIMOYA</div>
        <div className="card">
          {row(<I.scan/>,  "Avtomatik skaner",
            "Har 15 daqiqada papkalarni tekshiradi",
            <Toggle on={auto} onChange={setAuto}/>)}
          {row(<I.trash/>, "Xavfli APK'ni o'chirish",
            "Topilgan zararli fayl 5 sekunddan keyin avtomatik o'chiriladi",
            <Toggle on={autoDelete} onChange={setAutoDelete}/>)}
          {row(<I.bell/>,  "Fishing bildirishnomalari",
            "OTP kodli shubhali xabarlarni yashiradi",
            <Toggle on={phishBlock} onChange={setPhishBlock}/>)}
          <div className="li-row" style={{ cursor: "default", borderBottom: "none" }}>
            <div className="ico" style={{ background: "var(--primary-soft)", color: "var(--primary)" }}><I.zap/></div>
            <div className="meta">
              <div className="h">Fon xizmati</div>
              <div style={{ font: "400 11.5px/1.4 var(--font-body)", color: "var(--ink-2)", marginTop: 3 }}>WorkManager · har 15 daqiqada</div>
            </div>
            <Toggle on={bg} onChange={setBg}/>
          </div>
        </div>
      </div>

      <div style={{ marginTop: 18 }}>
        <div className="h-eyebrow" style={{ marginBottom: 8 }}>SERVER</div>
        <div className="card">
          {row(<I.server/>, "Bulutga yuklash",
            "Xavfli APK'lar tahlil uchun serverga yuboriladi",
            <Toggle on={serverUpload} onChange={setServerUpload}/>)}
          <div className="li-row" style={{ cursor: "pointer", borderBottom: "none" }}>
            <div className="ico" style={{ background: "var(--primary-soft)", color: "var(--primary)" }}><I.globe/></div>
            <div className="meta">
              <div className="h">Server manzili</div>
              <div className="s mono">https://api.kiberqalqon.uz</div>
            </div>
            <I.chevR/>
          </div>
        </div>
      </div>

      {/* Appearance — wired to tweaks */}
      <div style={{ marginTop: 18 }}>
        <div className="h-eyebrow" style={{ marginBottom: 8 }}>KO'RINISH</div>
        <div className="card" style={{ padding: 14 }}>
          {/* Theme switch */}
          <div className="between">
            <div>
              <div style={{ font: "600 14px/1.2 var(--font-display)", color: "var(--ink)" }}>Tema</div>
              <div style={{ font: "400 11.5px/1.4 var(--font-body)", color: "var(--ink-2)", marginTop: 3 }}>
                Tunda terminal-ko'k, kunduzi do'stona oq
              </div>
            </div>
            <div style={{
              display: "inline-flex", padding: 3, borderRadius: 999,
              background: "var(--bg-sunken)", border: "1px solid var(--hairline)",
            }}>
              {[
                { id: "light", icon: <I.sun size={14}/>, label: "Kun" },
                { id: "dark",  icon: <I.moon size={14}/>, label: "Tun" },
              ].map(t => (
                <button key={t.id} onClick={() => setTweak("theme", t.id)} style={{
                  display: "inline-flex", alignItems: "center", gap: 5,
                  padding: "6px 12px", borderRadius: 999,
                  background: tweaks.theme === t.id ? "var(--primary)" : "transparent",
                  color: tweaks.theme === t.id ? "var(--on-primary)" : "var(--ink-2)",
                  border: "none", cursor: "pointer",
                  font: "600 12px/1 var(--font-display)",
                }}>{t.icon}{t.label}</button>
              ))}
            </div>
          </div>

          <div style={{ height: 1, background: "var(--hairline)", margin: "12px 0" }}/>

          {/* Accent color */}
          <div style={{ font: "600 14px/1.2 var(--font-display)", color: "var(--ink)" }}>Asosiy rang</div>
          <div style={{ font: "400 11.5px/1.4 var(--font-body)", color: "var(--ink-2)", marginTop: 3, marginBottom: 12 }}>
            Milliy palitra — feruz, za'faron, anor
          </div>
          <div style={{ display: "flex", gap: 10 }}>
            {[
              { id: "turquoise", hex: "oklch(0.62 0.13 202)", name: "Feruz" },
              { id: "saffron",   hex: "oklch(0.74 0.14 70)",  name: "Za'faron" },
              { id: "pomegranate", hex: "oklch(0.62 0.18 18)", name: "Anor" },
            ].map(c => (
              <button key={c.id} onClick={() => setTweak("accent", c.id)} style={{
                flex: 1,
                background: tweaks.accent === c.id ? "var(--bg-sunken)" : "transparent",
                border: tweaks.accent === c.id ? "1px solid var(--primary)" : "1px solid var(--hairline)",
                borderRadius: 14, padding: 10, cursor: "pointer",
                display: "flex", flexDirection: "column", alignItems: "center", gap: 6,
              }}>
                <span style={{
                  width: 28, height: 28, borderRadius: 8,
                  background: c.hex,
                }}/>
                <span style={{ font: "600 11px/1 var(--font-display)", color: "var(--ink)" }}>{c.name}</span>
              </button>
            ))}
          </div>
        </div>
      </div>

      <div style={{ marginTop: 18 }}>
        <div className="h-eyebrow" style={{ marginBottom: 8 }}>TIL</div>
        <div className="card">
          {[
            { id: "uz", n: "O'zbekcha", flag: "UZ" },
            { id: "ru", n: "Русский",   flag: "RU" },
          ].map((l, i, arr) => (
            <div key={l.id}
              onClick={() => setTweak("lang", l.id)}
              className="li-row"
              style={{ borderBottom: i < arr.length - 1 ? "1px solid var(--hairline)" : "none" }}>
              <div className="ico" style={{
                background: "var(--bg-sunken)",
                color: "var(--ink)",
                fontFamily: "var(--font-mono)",
                fontWeight: 700, fontSize: 12, letterSpacing: "0.05em",
              }}>{l.flag}</div>
              <div className="meta">
                <div className="h">{l.n}</div>
              </div>
              {tweaks.lang === l.id
                ? <I.check size={20}/>
                : <I.chevR/>}
            </div>
          ))}
        </div>
      </div>

      <div style={{ marginTop: 18 }}>
        <div className="h-eyebrow" style={{ marginBottom: 8 }}>HAQIDA</div>
        <div className="card">
          {[
            { i: <I.info/>, n: "Loyiha haqida", s: "KiberQalqon — bepul ochiq kodli himoya" },
            { i: <I.shield/>, n: "Yordam markazi", s: "Savollarga javob" },
            { i: <I.lock/>,   n: "Maxfiylik siyosati", s: "Hech qanday shaxsiy ma'lumot yuborilmaydi" },
          ].map((x, i, arr) => (
            <div key={x.n} className="li-row"
              style={{ borderBottom: i < arr.length - 1 ? "1px solid var(--hairline)" : "none" }}>
              <div className="ico" style={{ background: "var(--primary-soft)", color: "var(--primary)" }}>{x.i}</div>
              <div className="meta">
                <div className="h">{x.n}</div>
                <div className="s">{x.s}</div>
              </div>
              <I.chevR/>
            </div>
          ))}
        </div>
      </div>

      <div style={{
        marginTop: 24, marginBottom: 4,
        textAlign: "center",
        font: "500 10.5px/1.45 var(--font-mono)",
        color: "var(--ink-3)",
        letterSpacing: "0.08em",
      }}>
        KIBERQALQON v7.5 · BUILD 9995<br/>
        © 2026 · Made in Tashkent
      </div>
    </div>
  );
};

/* ── Statistics screen ────────────────────────────────────── */
const StatsScreen = ({ goto }) => {
  // Mini bar chart data (last 7 days)
  const week = [
    { d: "Du", v: 32, t: 0 },
    { d: "Se", v: 41, t: 1 },
    { d: "Cho", v: 38, t: 0 },
    { d: "Pa", v: 52, t: 2 },
    { d: "Ju", v: 47, t: 1 },
    { d: "Sh", v: 28, t: 0 },
    { d: "Ya", v: 9,  t: 1 },
  ];
  const maxV = Math.max(...week.map(w => w.v));

  return (
    <div className="screen-pad fade-in" style={{ paddingTop: 6 }}>
      <div className="head-row">
        <div>
          <div className="h-eyebrow">STATISTIKA</div>
          <div className="h-title" style={{ marginTop: 6 }}>So'nggi 7 kun</div>
          <div className="h-sub">Sizning telefoningiz himoyasi</div>
        </div>
      </div>

      {/* Big totals */}
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
        <div className="card" style={{ padding: 14 }}>
          <div className="row" style={{ gap: 6 }}>
            <I.scan size={16}/>
            <span style={{ font: "500 11px/1 var(--font-mono)", letterSpacing: "0.1em", textTransform: "uppercase", color: "var(--ink-3)" }}>JAMI</span>
          </div>
          <div style={{ font: "800 32px/1 var(--font-display)", color: "var(--ink)", marginTop: 8, letterSpacing: "-0.02em" }}>
            247
          </div>
          <div style={{ font: "400 12px/1.4 var(--font-body)", color: "var(--ink-2)", marginTop: 4 }}>
            tekshirilgan APK
          </div>
        </div>
        <div className="card" style={{ padding: 14 }}>
          <div className="row" style={{ gap: 6, color: "var(--danger)" }}>
            <I.bug size={16}/>
            <span style={{ font: "500 11px/1 var(--font-mono)", letterSpacing: "0.1em", textTransform: "uppercase" }}>BLOKLANDI</span>
          </div>
          <div style={{ font: "800 32px/1 var(--font-display)", color: "var(--danger)", marginTop: 8, letterSpacing: "-0.02em" }}>
            5
          </div>
          <div style={{ font: "400 12px/1.4 var(--font-body)", color: "var(--ink-2)", marginTop: 4 }}>
            zararli oilalar
          </div>
        </div>
      </div>

      {/* Week chart */}
      <div className="card" style={{ marginTop: 14, padding: "14px 14px" }}>
        <div className="between" style={{ marginBottom: 14 }}>
          <div>
            <div className="h-eyebrow">FAOLLIK</div>
            <div style={{ font: "700 16px/1.1 var(--font-display)", color: "var(--ink)", marginTop: 4 }}>
              Tekshiruvlar / kun
            </div>
          </div>
          <span className="chip outline">Hafta</span>
        </div>
        <div style={{
          display: "grid", gridTemplateColumns: "repeat(7, 1fr)", gap: 8,
          height: 130, alignItems: "end",
        }}>
          {week.map((d, i) => {
            const h = (d.v / maxV) * 100;
            return (
              <div key={i} style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 6 }}>
                <div style={{
                  width: "100%", height: `${h}%`,
                  background: d.t > 0
                    ? "linear-gradient(180deg, var(--danger) 0%, color-mix(in oklch, var(--danger) 60%, var(--bg-elev)) 100%)"
                    : "linear-gradient(180deg, var(--primary) 0%, var(--primary-2) 100%)",
                  borderRadius: 6,
                  position: "relative",
                }}>
                  {d.t > 0 && (
                    <span style={{
                      position: "absolute", top: -16, left: "50%", transform: "translateX(-50%)",
                      font: "700 9px/1 var(--font-mono)",
                      color: "var(--danger)",
                      background: "var(--danger-bg)",
                      padding: "2px 5px", borderRadius: 4, whiteSpace: "nowrap",
                    }}>{d.t}</span>
                  )}
                </div>
              </div>
            );
          })}
        </div>
        <div style={{
          display: "grid", gridTemplateColumns: "repeat(7, 1fr)", gap: 8,
          marginTop: 8,
        }}>
          {week.map((d, i) => (
            <div key={i} style={{
              font: "600 10px/1 var(--font-mono)",
              color: "var(--ink-3)",
              letterSpacing: "0.05em",
              textAlign: "center",
            }}>{d.d}</div>
          ))}
        </div>
      </div>

      {/* Malware families breakdown */}
      <div className="head-row" style={{ marginTop: 22, marginBottom: 10 }}>
        <div>
          <div className="h-eyebrow">OILALAR</div>
          <div style={{ font: "700 18px/1.2 var(--font-display)", color: "var(--ink)", marginTop: 4 }}>
            Aniqlangan tahdidlar
          </div>
        </div>
      </div>
      <div className="card" style={{ padding: "14px 14px" }}>
        {[
          { n: "Ajina.Banker", v: 4, max: 5, c: "var(--danger)" },
          { n: "RoundRift",    v: 1, max: 5, c: "var(--danger)" },
          { n: "SMS Stealer",  v: 0, max: 5, c: "var(--warn)" },
          { n: "Phish overlay",v: 0, max: 5, c: "var(--warn)" },
        ].map(f => (
          <div key={f.n} className="bar-row">
            <div className="label" style={{ width: 90, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{f.n}</div>
            <div className="track">
              <div className="fill" style={{ width: `${(f.v / f.max) * 100}%`, background: f.c }}/>
            </div>
            <div className="v">{f.v}</div>
          </div>
        ))}
      </div>

      {/* Sample comparison table */}
      <div className="head-row" style={{ marginTop: 22, marginBottom: 10 }}>
        <div>
          <div className="h-eyebrow">5 NAMUNA</div>
          <div style={{ font: "700 18px/1.2 var(--font-display)", color: "var(--ink)", marginTop: 4 }}>
            Topilgan tahdidlar solishtirmasi
          </div>
          <div className="h-sub" style={{ marginTop: 4 }}>2026-05-21 da tahlil qilingan APK'lar</div>
        </div>
      </div>

      <div className="card" style={{ padding: 12 }}>
        {APK_SAMPLES.filter(a => a.color === "danger").map((a, i, arr) => (
          <div key={a.id} onClick={() => goto("result", a.id)} style={{
            padding: "10px 6px",
            borderBottom: i < arr.length - 1 ? "1px solid var(--hairline)" : "none",
            cursor: "pointer",
          }}>
            <div className="between" style={{ alignItems: "flex-start" }}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{
                  font: "700 13px/1.2 var(--font-display)", color: "var(--ink)",
                  whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis",
                }}>{a.name}</div>
                <div className="mono" style={{
                  font: "500 10.5px/1.3 var(--font-mono)", color: "var(--ink-3)",
                  marginTop: 3,
                }}>{a.pkg}</div>
              </div>
              <span className={`sev ${a.sev}`}><span className="dot"/>{a.label}</span>
            </div>
            <div className="row" style={{ marginTop: 6, gap: 6, flexWrap: "wrap" }}>
              <span style={{
                font: "600 10px/1 var(--font-mono)", padding: "3px 6px",
                background: "var(--bg-sunken)", color: "var(--ink-2)",
                borderRadius: 4, letterSpacing: "0.04em",
              }}>{a.family}</span>
              <span style={{
                font: "500 10px/1 var(--font-mono)", padding: "3px 6px",
                background: "var(--bg-sunken)", color: "var(--ink-2)",
                borderRadius: 4,
              }}>{a.size}</span>
              <span style={{
                font: "500 10px/1 var(--font-mono)", padding: "3px 6px",
                background: "var(--bg-sunken)", color: "var(--ink-2)",
                borderRadius: 4,
              }}>SHA1: {a.sha1}</span>
            </div>
          </div>
        ))}
      </div>

      {/* C2 map (geographic, simplified) */}
      <div className="head-row" style={{ marginTop: 22, marginBottom: 10 }}>
        <div>
          <div className="h-eyebrow">TARMOQ XARITASI</div>
          <div style={{ font: "700 18px/1.2 var(--font-display)", color: "var(--ink)", marginTop: 4 }}>
            C2 manzillar
          </div>
        </div>
      </div>
      <div className="card" style={{ padding: 14, position: "relative", overflow: "hidden" }}>
        <div style={{
          height: 140,
          borderRadius: 12,
          background:
            "radial-gradient(circle at 65% 40%, var(--primary-soft), transparent 60%)," +
            "linear-gradient(135deg, var(--bg-sunken), color-mix(in oklch, var(--primary) 6%, var(--bg-sunken)))",
          position: "relative",
          overflow: "hidden",
        }}>
          {/* dot grid (world hint) */}
          <svg width="100%" height="100%" viewBox="0 0 320 140" preserveAspectRatio="none">
            {Array.from({length: 16}).map((_, x) =>
              Array.from({length: 8}).map((_, y) => (
                <circle key={`${x}-${y}`} cx={10 + x*20} cy={10 + y*16} r="1.4"
                  fill="var(--ink-3)" opacity={0.25}/>
              ))
            )}
            {/* Tashkent (us) */}
            <g transform="translate(195, 60)">
              <circle r="14" fill="var(--primary)" opacity="0.18"/>
              <circle r="6" fill="var(--primary)"/>
              <text x="12" y="4" fill="var(--ink)" style={{ font: "600 10px var(--font-mono)" }}>TASHKENT</text>
            </g>
            {/* C2 hosts (red) */}
            <g transform="translate(125, 50)">
              <circle r="10" fill="var(--danger)" opacity="0.22"/>
              <circle r="4" fill="var(--danger)"/>
            </g>
            <g transform="translate(95, 75)">
              <circle r="10" fill="var(--danger)" opacity="0.22"/>
              <circle r="4" fill="var(--danger)"/>
            </g>
            {/* lines */}
            <line x1="135" y1="50" x2="195" y2="60" stroke="var(--danger)" strokeWidth="1" strokeDasharray="3 3" opacity="0.6"/>
            <line x1="105" y1="75" x2="195" y2="60" stroke="var(--danger)" strokeWidth="1" strokeDasharray="3 3" opacity="0.6"/>
          </svg>
        </div>

        <div style={{ marginTop: 12, display: "flex", flexDirection: "column", gap: 8 }}>
          {[
            { d: "elrxzx.com", t: "C2 asosiy", n: "Ajina.Banker" },
            { d: "ilovekkksfm.com", t: "Dropper", n: "RoundRift" },
          ].map(c => (
            <div key={c.d} className="between" style={{
              padding: "8px 12px", borderRadius: 10,
              background: "var(--bg-sunken)",
              border: "1px solid var(--hairline)",
            }}>
              <div className="row" style={{ gap: 8 }}>
                <span style={{ width: 8, height: 8, borderRadius: "50%", background: "var(--danger)" }}/>
                <div>
                  <div className="mono" style={{ font: "600 11.5px/1 var(--font-mono)", color: "var(--ink)" }}>{c.d}</div>
                  <div style={{ font: "500 10px/1 var(--font-mono)", color: "var(--ink-3)", marginTop: 3, letterSpacing: "0.06em" }}>
                    {c.t} · {c.n}
                  </div>
                </div>
              </div>
              <span className="chip danger" style={{ height: 22 }}>BLOK</span>
            </div>
          ))}
        </div>
      </div>

      {/* Footer cta */}
      <button className="btn ghost block" style={{ marginTop: 18 }} onClick={() => goto("apk")}>
        <I.refresh size={16}/> Hisobotni yangilash
      </button>
    </div>
  );
};

Object.assign(window, { SettingsScreen, StatsScreen, Toggle });
