/* ============================================================
   ANOR QALQON — Til · Splash · Onboarding · Dashboard · Skaner
   ============================================================ */
const { useState, useEffect, useRef } = React;

const starBg = (opacityMul = 1) => ({
  position: "absolute", inset: 0, pointerEvents: "none",
  opacity: `calc(var(--ornament) * ${opacityMul})`,
  backgroundImage: `url("data:image/svg+xml,${window.STAR_TILE}")`,
  backgroundSize: "78px 78px", backgroundRepeat: "repeat",
});
const shortTime = (t) => t.replace("Bugun, ", "").replace("Kecha, ", "kecha ");

/* ─────────────── TIL TANLASH ─────────────── */
function Language({ go }) {
  const [sel, setSel] = useState("uz");
  return (
    <div className="fade" style={{ position: "absolute", inset: 0, display: "flex", flexDirection: "column",
      padding: "0 22px", color: "var(--ink)" }}>
      <div style={{ flex: 1, display: "grid", placeItems: "center" }}>
        <div style={{ textAlign: "center" }}>
          <LogoDisc size={104} />
          <div style={{ font: "800 30px/1 var(--font-display)", letterSpacing: "-.03em", marginTop: 22 }}>ANOR QALQON</div>
          <div className="mono" style={{ fontSize: 11.5, letterSpacing: ".18em", color: "var(--ink-3)", marginTop: 10 }}>MILLIY KIBER HIMOYA</div>
        </div>
      </div>
      <div style={{ paddingBottom: 28 }}>
        <div style={{ font: "600 14px/1 var(--font-display)", color: "var(--ink-3)", marginBottom: 12, textAlign: "center" }}>Tilni tanlang · Выберите язык</div>
        {[["uz", "O'zbekcha", "UZ"], ["ru", "Русский", "RU"]].map(([k, l, c]) => (
          <button key={k} onClick={() => setSel(k)} className="card"
            style={{ width: "100%", display: "flex", alignItems: "center", gap: 14, padding: 15, marginBottom: 10,
              border: sel === k ? "1.5px solid var(--primary)" : "1px solid var(--hairline)",
              background: sel === k ? "var(--primary-soft)" : "var(--surface)", cursor: "pointer" }}>
            <div className="mono" style={{ width: 44, height: 44, borderRadius: "var(--r-md)", background: "var(--surface-2)",
              display: "grid", placeItems: "center", font: "700 14px/1 var(--font-mono)", color: "var(--ink-2)" }}>{c}</div>
            <span className="grow" style={{ textAlign: "left", font: "600 16px/1 var(--font-display)" }}>{l}</span>
            <span style={{ color: sel === k ? "var(--primary)" : "var(--ink-3)" }}>
              {sel === k ? I.checkCircle({ size: 22 }) : <span style={{ width: 22, height: 22, borderRadius: "50%", border: "2px solid var(--hairline-2)", display: "block" }} />}
            </span>
          </button>
        ))}
        <button className="btn primary block lg" style={{ marginTop: 12 }} onClick={() => go("splash")}>Davom etish</button>
      </div>
    </div>
  );
}

/* ─────────────── SPLASH ─────────────── */
function Splash({ go }) {
  useEffect(() => { const t = setTimeout(() => go("onboarding"), 2200); return () => clearTimeout(t); }, []);
  return (
    <div className="fade" style={{ position: "absolute", inset: 0, display: "flex", flexDirection: "column",
      alignItems: "center", justifyContent: "center", color: "#fff",
      background: "linear-gradient(165deg, var(--primary), var(--primary-2))" }}>
      <div style={{ ...starBg(3), opacity: .12, filter: "brightness(3)" }} />
      <div style={{ position: "relative", display: "grid", placeItems: "center", width: 170, height: 170 }}>
        <div className="pulse-ring" style={{ background: "rgba(255,255,255,.16)" }} />
        <div className="pulse-ring" style={{ background: "rgba(255,255,255,.12)", animationDelay: ".8s" }} />
        <LogoDisc size={116} />
      </div>
      <div style={{ font: "800 34px/1 var(--font-display)", letterSpacing: "-.03em", marginTop: 32 }}>ANOR QALQON</div>
      <div className="mono" style={{ fontSize: 12, letterSpacing: ".22em", opacity: .9, marginTop: 12 }}>MILLIY KIBER HIMOYA</div>
      <div style={{ position: "absolute", bottom: 54, display: "flex", gap: 8 }}>
        {[0,1,2].map(i => <span key={i} style={{ width: 9, height: 9, borderRadius: "50%", background: "#fff",
          animation: "dots 1.3s infinite", animationDelay: `${i*.16}s` }} />)}
      </div>
    </div>
  );
}

/* ─────────────── ONBOARDING ─────────────── */
const ONB = [
  { icon: "scan", n: "1", title: "Telefoningizni o'zi tekshiradi",
    body: "Anor Qalqon Telegram, WhatsApp va yuklab olingan fayllarni o'zi kuzatib turadi. Siz hech narsa qilishingiz shart emas." },
  { icon: "shieldAlert", n: "2", title: "Xavfli dasturlarni topadi",
    body: "Rasm yoki video deb yashiringan zararli fayllar aniqlanadi. Ular bank parolingiz va SMS kodlaringizni o'g'irlashga urinadi." },
  { icon: "checkCircle", n: "3", title: "Sodda til, kuchli himoya",
    body: "Hamma narsa o'zbek tilida, oddiy so'zlar bilan tushuntiriladi. Murakkab texnik atamalar yo'q — faqat siz uchun muhim narsa." },
];
function Onboarding({ go }) {
  const [i, setI] = useState(0);
  const s = ONB[i]; const last = i === ONB.length - 1;
  return (
    <div className="fade" style={{ position: "absolute", inset: 0, display: "flex", flexDirection: "column" }}>
      <div style={{ display: "flex", justifyContent: "flex-end", padding: "10px 16px" }}>
        <button style={{ background: "none", border: "none", padding: 8, color: "var(--ink-3)",
          font: "600 14px/1 var(--font-display)", cursor: "pointer" }} onClick={() => go("initialscan")}>O'tkazib yuborish</button>
      </div>
      <div style={{ flex: 1, display: "grid", placeItems: "center", position: "relative" }}>
        <div style={starBg(1.2)} />
        <div style={{ position: "relative", width: 176, height: 176, display: "grid", placeItems: "center" }}>
          <div style={{ position: "absolute", inset: 0, borderRadius: "50%", background: "var(--primary-soft)" }} />
          <div style={{ position: "absolute", inset: 28, borderRadius: "50%", background: "var(--surface)", boxShadow: "var(--shadow-1)" }} />
          <div style={{ position: "relative", color: "var(--primary)" }}>{I[s.icon]({ size: 62, sw: 1.7 })}</div>
        </div>
      </div>
      <div className="screen-pad" style={{ paddingBottom: 26 }}>
        <div className="eyebrow">{s.n} · 3</div>
        <h1 className="h-title" style={{ margin: "10px 0 12px", textWrap: "balance" }}>{s.title}</h1>
        <p className="h-sub" style={{ minHeight: 90 }}>{s.body}</p>
        <div style={{ display: "flex", gap: 7, justifyContent: "center", margin: "16px 0 18px" }}>
          {ONB.map((_, k) => <span key={k} style={{ height: 8, borderRadius: 99, transition: "all .25s var(--ease)",
            width: k === i ? 26 : 8, background: k === i ? "var(--primary)" : "var(--hairline-2)" }} />)}
        </div>
        <button className="btn primary block lg" onClick={() => last ? go("initialscan") : setI(i + 1)}>
          {last ? "Himoyani yoqish" : "Davom etish"}
        </button>
      </div>
    </div>
  );
}

/* ─────────────── TOP BAR (reusable) ─────────────── */
function TopBar({ go, title = "Anor Qalqon", sub }) {
  return (
    <div className="between" style={{ padding: "6px 18px 12px" }}>
      <div className="row" style={{ gap: 11 }}>
        <Logo size={40} radius={12} />
        <div className="col">
          <div style={{ font: "700 17px/1 var(--font-display)", letterSpacing: "-.02em" }}>{title}</div>
          <div className="row" style={{ gap: 6, marginTop: 5 }}>
            <span style={{ width: 7, height: 7, borderRadius: "50%", background: "var(--safe)" }} />
            <span style={{ font: "600 12px/1 var(--font-display)", color: "var(--ink-3)" }}>{sub || "Himoya yoqilgan"}</span>
          </div>
        </div>
      </div>
      <div className="row" style={{ gap: 9 }}>
        <button className="icon-btn" style={{ font: "700 12px/1 var(--font-mono)" }} onClick={() => go("language")}>UZ</button>
        <button className="icon-btn" onClick={() => go("quarantine")}>{I.bell({ size: 20 })}</button>
      </div>
    </div>
  );
}

/* ─────────────── DASHBOARD ─────────────── */
function Dashboard({ go, openThreat, scan }) {
  return (
    <div className="fade" style={{ paddingBottom: 8 }}>
      <TopBar go={go} />
      <div className="screen-pad" style={{ paddingTop: 4 }}>

        {/* HERO */}
        <div className="card" style={{ overflow: "hidden", padding: 0 }}>
          <div style={starBg(2.4)} />
          <div style={{ position: "relative", padding: "26px 20px 20px", display: "flex", flexDirection: "column",
            alignItems: "center", textAlign: "center" }}>
            <Ring value={100} size={158} stroke={13} color="var(--safe)" track="var(--surface-2)">
              <div style={{ color: "var(--safe)" }}>{I.shieldCheck({ size: 54, sw: 1.7 })}</div>
            </Ring>
            <h1 className="h-title" style={{ marginTop: 18, fontSize: 25 }}>Telefoningiz himoyalangan</h1>
            <div className="row" style={{ gap: 6, marginTop: 8, color: "var(--ink-3)" }}>
              {I.clock({ size: 15 })}
              <span style={{ font: "500 13.5px/1 var(--font-body)" }}>So'nggi tekshiruv: 12 daqiqa oldin</span>
            </div>
            <div className="row" style={{ width: "100%", marginTop: 20, gap: 10 }}>
              <button className="btn primary grow lg" onClick={scan}>{I.scan({ size: 20 })} Hozir tekshirish</button>
              <button className="icon-btn" style={{ width: 58, height: 58, borderRadius: "var(--r-lg)" }} onClick={scan}>{I.refresh({ size: 22 })}</button>
            </div>
          </div>
        </div>

        {/* 3 raqam */}
        <div style={{ display: "grid", gridTemplateColumns: "repeat(3,1fr)", gap: 10, marginTop: 12 }}>
          {[
            { n: "247", t: "Tekshirildi", c: "var(--ink)", go: "apps" },
            { n: "5", t: "O'chirildi", c: "var(--danger)", go: "quarantine" },
            { n: "100%", t: "Himoya", c: "var(--safe)", go: "protection" },
          ].map((s, i) => (
            <button key={i} className="card" style={{ padding: "15px 12px", textAlign: "left", cursor: "pointer", border: "1px solid var(--hairline)" }} onClick={() => go(s.go)}>
              <div style={{ font: "var(--display-weight) 26px/1 var(--font-display)", letterSpacing: "-.03em", color: s.c }}>{s.n}</div>
              <div style={{ font: "500 12.5px/1.2 var(--font-body)", color: "var(--ink-3)", marginTop: 7 }}>{s.t}</div>
            </button>
          ))}
        </div>

        {/* Bugun bloklangan */}
        <div className="between" style={{ marginTop: 24, marginBottom: 10 }}>
          <h2 className="sec-title">Bugun bloklangan</h2>
          <span className="link" onClick={() => go("apps")}>Hammasi</span>
        </div>
        <div className="card" style={{ overflow: "hidden" }}>
          {THREATS.map(t => (
            <div key={t.id} className="li" onClick={() => openThreat(t)}>
              <div className="av danger">{I.file({ size: 22 })}</div>
              <div className="grow">
                <div className="ttl">{t.name}</div>
                <div className="sub">{t.from} orqali · {shortTime(t.time)}</div>
              </div>
              <span className="tag danger"><span className="dot" />{t.sevLabel}</span>
            </div>
          ))}
        </div>

        {/* Himoya qatlamlari */}
        <h2 className="sec-title" style={{ marginTop: 24, marginBottom: 10 }}>Himoya qatlamlari</h2>
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
          {GUARDS.map((g, i) => (
            <div key={i} className="card" style={{ padding: 15 }}>
              <div className="between" style={{ alignItems: "flex-start" }}>
                <div style={{ width: 42, height: 42, borderRadius: "var(--r-md)", background: "var(--primary-soft)",
                  color: "var(--primary)", display: "grid", placeItems: "center" }}>{I[g.icon]({ size: 21 })}</div>
                <span style={{ width: 9, height: 9, borderRadius: "50%", background: "var(--safe)", boxShadow: "0 0 0 4px var(--safe-bg)" }} />
              </div>
              <div style={{ font: "700 14.5px/1.2 var(--font-display)", letterSpacing: "-.01em", marginTop: 13 }}>{g.t}</div>
              <div style={{ font: "400 12px/1.35 var(--font-body)", color: "var(--ink-3)", marginTop: 5 }}>{g.s}</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

/* ─────────────── SKANER (apps list) ─────────────── */
function Apps({ go, openThreat, scan }) {
  const [f, setF] = useState("all");
  const filtered = APPS.filter(a => f === "all" ? true : f === "bad" ? !a.safe : a.safe);
  const counts = { all: APPS.length, bad: APPS.filter(a => !a.safe).length, ok: APPS.filter(a => a.safe).length };
  return (
    <div className="fade screen-pad">
      <div className="between" style={{ marginBottom: 16 }}>
        <div>
          <div className="eyebrow">SKANER</div>
          <h1 className="h-title" style={{ marginTop: 8 }}>Tekshirilgan fayllar</h1>
        </div>
        <button className="icon-btn" onClick={scan}>{I.scan({ size: 20 })}</button>
      </div>

      {/* live status strip */}
      <div className="card" style={{ padding: 14, marginBottom: 14, display: "flex", alignItems: "center", gap: 12,
        background: "var(--surface)", borderColor: "var(--hairline)" }}>
        <div style={{ width: 42, height: 42, borderRadius: "var(--r-md)", background: "var(--safe-bg)", color: "var(--safe)", display: "grid", placeItems: "center" }}>{I.checkCircle({ size: 22 })}</div>
        <div className="grow">
          <div style={{ font: "700 14.5px/1 var(--font-display)" }}>Avto-himoya yoniq</div>
          <div style={{ font: "400 12.5px/1.3 var(--font-body)", color: "var(--ink-3)", marginTop: 4 }}>Yangi fayllar avtomatik tekshiriladi</div>
        </div>
        <span className="tag safe"><span className="dot" />Jonli</span>
      </div>

      <div style={{ display: "flex", gap: 8, marginBottom: 14 }}>
        {[["all","Hammasi"],["bad","Xavfli"],["ok","Xavfsiz"]].map(([k, l]) => (
          <button key={k} className={"chip" + (f === k ? " on" : "")} onClick={() => setF(k)}>
            {l}<span className="count">{counts[k]}</span>
          </button>
        ))}
      </div>
      <div className="card" style={{ overflow: "hidden" }}>
        {filtered.map(a => (
          <div key={a.id} className="li" onClick={() => { const t = THREATS.find(x => x.id === a.tId); if (t) openThreat(t); }}>
            <div className={"av " + (a.safe ? "safe" : "danger")}>{a.safe ? I.checkCircle({ size: 22 }) : I.file({ size: 22 })}</div>
            <div className="grow">
              <div className="ttl">{a.name}</div>
              <div className="sub">{a.from} · {a.size}</div>
            </div>
            <span className={"tag " + (a.safe ? "safe" : "danger")}><span className="dot" />{a.label}</span>
          </div>
        ))}
      </div>
      <button className="btn ghost block" style={{ marginTop: 16 }} onClick={scan}>
        {I.refresh({ size: 19 })} Hammasini qayta tekshirish
      </button>
    </div>
  );
}

Object.assign(window, { Language, Splash, Onboarding, Dashboard, Apps, TopBar });
