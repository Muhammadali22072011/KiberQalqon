/* ============================================================
   ANOR QALQON — AutoScan · ScanResult · InitialScan
   Texnik jargon YO'Q — sodda, tushunarli ogohlantirishlar.
   ============================================================ */

/* ─────────────── AUTO-SCAN (fullscreen alert) ─────────────── */
function AutoScan({ go, openThreat, scanMode }) {
  const threat = window.THREATS[0];
  const [pct, setPct] = useState(0);
  const [phase, setPhase] = useState("scan"); // scan | result
  const [count, setCount] = useState(5);
  const steps = [
    "Fayl ochilmoqda",
    "Ruxsatlar tekshirilmoqda",
    "Bank himoyasi tekshirilmoqda",
    "Zararli belgilar qidirilmoqda",
  ];
  const [done, setDone] = useState(0);

  useEffect(() => {
    let p = 0;
    const iv = setInterval(() => {
      p += 4; setPct(Math.min(p, 100));
      setDone(Math.min(steps.length, Math.floor(p / 25) + (p % 25 > 12 ? 1 : 0)));
      if (p >= 100) { clearInterval(iv); setTimeout(() => setPhase("result"), 450); }
    }, 70);
    return () => clearInterval(iv);
  }, []);

  useEffect(() => {
    if (phase !== "result") return;
    const iv = setInterval(() => setCount(c => Math.max(0, c - 1)), 1000);
    return () => clearInterval(iv);
  }, [phase]);

  if (phase === "scan") {
    return (
      <div className="fade" style={{ position: "absolute", inset: 0, color: "#fff", display: "flex", flexDirection: "column",
        alignItems: "center", justifyContent: "center", padding: 24,
        background: "linear-gradient(180deg, #1a1410, #0c0908)" }}>
        <div style={{ ...starBg(2), opacity: .08, filter: "brightness(4)" }} />
        <div style={{ position: "relative", width: 210, height: 210, display: "grid", placeItems: "center" }}>
          <div className="pulse-ring" style={{ background: "rgba(229,80,90,.16)" }} />
          <Ring value={pct} size={210} stroke={6} color="var(--primary)" track="rgba(255,255,255,.10)">
            <div style={{ textAlign: "center" }}>
              <div style={{ font: "800 42px/1 var(--font-display)", letterSpacing: "-.02em" }}>{pct}%</div>
              <div className="mono" style={{ fontSize: 11, letterSpacing: ".12em", opacity: .6, marginTop: 8 }}>TEKSHIRILMOQDA</div>
            </div>
          </Ring>
        </div>
        <div style={{ font: "700 21px/1.2 var(--font-display)", marginTop: 30, textAlign: "center" }}>Yangi fayl tekshirilmoqda</div>
        <div className="mono" style={{ fontSize: 12.5, opacity: .7, marginTop: 10 }}>{threat.name}</div>
        <div style={{ width: "100%", maxWidth: 300, marginTop: 26, display: "flex", flexDirection: "column", gap: 11 }}>
          {steps.map((s, i) => (
            <div key={i} className="row" style={{ gap: 10, opacity: i < done ? 1 : .4, transition: "opacity .3s" }}>
              <span style={{ width: 22, height: 22, borderRadius: "50%", display: "grid", placeItems: "center", flexShrink: 0,
                background: i < done ? "var(--safe)" : "rgba(255,255,255,.12)", color: "#fff" }}>
                {i < done ? I.check({ size: 13, sw: 3 }) : <span style={{ width: 6, height: 6, borderRadius: "50%", background: "rgba(255,255,255,.5)" }} />}
              </span>
              <span style={{ font: "500 14px/1.2 var(--font-body)" }}>{s}</span>
            </div>
          ))}
        </div>
      </div>
    );
  }

  // RESULT — danger
  return (
    <div className="fade" style={{ position: "absolute", inset: 0, color: "#fff", display: "flex", flexDirection: "column",
      background: "radial-gradient(ellipse 80% 55% at 50% 22%, rgba(224,67,47,.5), transparent 62%), linear-gradient(180deg, #2a1410, #0c0806)" }}>
      <div style={{ flex: 1, overflowY: "auto", padding: "44px 22px 16px", display: "flex", flexDirection: "column", alignItems: "center", textAlign: "center" }}>
        <div style={{ width: 96, height: 96, borderRadius: "50%", background: "var(--danger)", display: "grid", placeItems: "center",
          boxShadow: "0 0 0 10px rgba(224,67,47,.18), 0 0 0 22px rgba(224,67,47,.10)" }}>{I.alert({ size: 48, sw: 2 })}</div>
        <h1 style={{ font: "800 30px/1.1 var(--font-display)", letterSpacing: "-.02em", marginTop: 24 }}>Xavfli fayl topildi!</h1>
        <p style={{ font: "400 15px/1.5 var(--font-body)", color: "rgba(255,255,255,.82)", marginTop: 12, maxWidth: 320 }}>{threat.short}</p>

        <div style={{ width: "100%", background: "rgba(255,255,255,.07)", border: "1px solid rgba(255,255,255,.12)",
          borderRadius: "var(--r-lg)", padding: 16, marginTop: 22, textAlign: "left" }}>
          <div className="between">
            <div className="row" style={{ gap: 11, minWidth: 0 }}>
              <div style={{ width: 40, height: 40, borderRadius: "var(--r-sm)", background: "rgba(255,255,255,.12)", display: "grid", placeItems: "center", flexShrink: 0 }}>{I.file({ size: 20 })}</div>
              <div style={{ minWidth: 0 }}>
                <div style={{ font: "700 14.5px/1.2 var(--font-display)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{threat.name}</div>
                <div style={{ font: "400 12px/1.2 var(--font-body)", color: "rgba(255,255,255,.6)", marginTop: 3 }}>{threat.from} · {threat.size}</div>
              </div>
            </div>
          </div>
          <div style={{ height: 1, background: "rgba(255,255,255,.1)", margin: "14px 0" }} />
          <div style={{ font: "600 11px/1 var(--font-display)", letterSpacing: ".04em", color: "rgba(255,255,255,.6)", textTransform: "uppercase", marginBottom: 10 }}>Bu fayl nima qiladi</div>
          <div style={{ display: "flex", flexDirection: "column", gap: 9 }}>
            {threat.does.map(k => { const d = window.DOES[k]; return (
              <div key={k} className="row" style={{ gap: 9 }}>
                <span style={{ color: "#ffb3aa", flexShrink: 0 }}>{I[d.icon]({ size: 17 })}</span>
                <span style={{ font: "500 13.5px/1.3 var(--font-body)" }}>{d.t}</span>
              </div>); })}
          </div>
        </div>

        <div className="row" style={{ gap: 8, marginTop: 18, color: "rgba(255,255,255,.6)" }}>
          {I.clock({ size: 15 })}
          <span className="mono" style={{ fontSize: 12.5 }}>{count > 0 ? `${count} soniyadan keyin avtomatik o'chiriladi` : "O'chirilmoqda…"}</span>
        </div>
      </div>
      <div style={{ padding: "12px 22px 26px", display: "flex", gap: 10 }}>
        <button className="btn danger grow lg" onClick={() => go("dashboard")}>{I.trash({ size: 20 })} Hozir o'chirish</button>
        <button className="btn lg" style={{ background: "rgba(255,255,255,.12)", color: "#fff", padding: "0 18px" }} onClick={() => openThreat(threat)}>Batafsil</button>
      </div>
    </div>
  );
}

/* ─────────────── SCAN RESULT (sodda, jargonsiz) ─────────────── */
function ScanResult({ go, threat }) {
  const t = threat || window.THREATS[0];
  const [deleted, setDeleted] = useState(false);
  return (
    <div className="fade" style={{ paddingBottom: 24 }}>
      {/* banner */}
      <div style={{ position: "relative", overflow: "hidden",
        background: "linear-gradient(160deg, var(--danger), #8e1d12)", color: "#fff",
        borderBottomLeftRadius: 26, borderBottomRightRadius: 26, padding: "14px 18px 22px" }}>
        <div style={{ ...starBg(2.5), opacity: .1, filter: "brightness(4)" }} />
        <div className="between" style={{ position: "relative" }}>
          <button className="icon-btn" style={{ background: "rgba(255,255,255,.14)", border: "none", color: "#fff" }} onClick={() => go("apps")}>{I.back({ size: 20 })}</button>
          <span style={{ font: "600 13px/1 var(--font-display)" }}>Tekshirish natijasi</span>
          <button className="icon-btn" style={{ background: "rgba(255,255,255,.14)", border: "none", color: "#fff" }}>{I.download({ size: 18 })}</button>
        </div>
        <div style={{ position: "relative", display: "flex", gap: 14, marginTop: 18, alignItems: "center" }}>
          <div style={{ width: 56, height: 56, borderRadius: "var(--r-md)", background: "rgba(255,255,255,.16)", border: "1px solid rgba(255,255,255,.22)", display: "grid", placeItems: "center", flexShrink: 0 }}>{I.file({ size: 28 })}</div>
          <div style={{ minWidth: 0 }}>
            <div className="mono" style={{ fontSize: 10.5, letterSpacing: ".14em", opacity: .8, textTransform: "uppercase" }}>{t.from} orqali keldi</div>
            <div style={{ font: "800 21px/1.15 var(--font-display)", letterSpacing: "-.015em", marginTop: 5, wordBreak: "break-word" }}>{t.name}</div>
          </div>
        </div>
        <div className="row" style={{ position: "relative", gap: 8, marginTop: 14 }}>
          <span className="tag" style={{ background: "rgba(0,0,0,.22)", color: "#fff" }}><span className="dot" style={{ background: "#ffb3aa" }} />Xavfli</span>
          <span style={{ font: "500 12.5px/1.3 var(--font-body)", color: "rgba(255,255,255,.85)" }}>O'zini «{t.looksLike}» qilib ko'rsatadi</span>
        </div>
      </div>

      <div className="screen-pad" style={{ paddingTop: 18 }}>
        {/* Bu nima */}
        <div className="card" style={{ padding: 16 }}>
          <div className="row" style={{ gap: 9, marginBottom: 10 }}>
            <span style={{ color: "var(--danger)" }}>{I.help({ size: 19 })}</span>
            <span className="sec-title" style={{ fontSize: 16 }}>Bu fayl nima?</span>
          </div>
          <p style={{ font: "400 14.5px/1.55 var(--font-body)", color: "var(--ink-2)", margin: 0 }}>{t.short}</p>
        </div>

        {/* Nima qiladi */}
        <h2 className="sec-title" style={{ fontSize: 16, marginTop: 22, marginBottom: 10 }}>Bu fayl nima qiladi?</h2>
        <div className="card" style={{ overflow: "hidden" }}>
          {t.does.map(k => { const d = window.DOES[k]; return (
            <div key={k} className="li" style={{ cursor: "default" }}>
              <div className="av danger" style={{ width: 44, height: 44 }}>{I[d.icon]({ size: 20 })}</div>
              <div className="grow"><div className="ttl" style={{ whiteSpace: "normal" }}>{d.t}</div></div>
            </div>); })}
        </div>

        {/* Qayerdan keldi */}
        <h2 className="sec-title" style={{ fontSize: 16, marginTop: 22, marginBottom: 10 }}>Qayerdan keldi?</h2>
        <div className="card" style={{ padding: 16, display: "flex", gap: 13, alignItems: "flex-start",
          background: "var(--warn-bg)", borderColor: "transparent" }}>
          <span style={{ color: "var(--warn-ink)", flexShrink: 0, marginTop: 1 }}>{I[t.fromIcon]({ size: 22 })}</span>
          <p style={{ font: "500 13.5px/1.5 var(--font-body)", color: "var(--warn-ink)", margin: 0 }}>{t.sourceWarn}</p>
        </div>

        {/* So'ralgan ruxsatlar (havola) */}
        <button className="card" onClick={() => go("permissions")} style={{ width: "100%", marginTop: 12, padding: 16,
          display: "flex", alignItems: "center", gap: 13, cursor: "pointer", border: "1px solid var(--hairline)", textAlign: "left" }}>
          <div className="av danger" style={{ width: 44, height: 44, borderRadius: "var(--r-md)", display: "grid", placeItems: "center", background: "var(--danger-bg)", color: "var(--danger)", flexShrink: 0 }}>{I.lock({ size: 21 })}</div>
          <div className="grow">
            <div className="ttl">So'ralgan ruxsatlar</div>
            <div className="sub">7 ta ruxsat · 4 tasi juda xavfli</div>
          </div>
          <span style={{ color: "var(--ink-3)" }}>{I.chevron({ size: 20 })}</span>
        </button>

        {/* actions */}
        <div style={{ marginTop: 24, display: "flex", flexDirection: "column", gap: 10 }}>
          {deleted ? (
            <div className="card" style={{ padding: 16, display: "flex", gap: 11, alignItems: "center", background: "var(--safe-bg)", borderColor: "transparent" }}>
              <span style={{ color: "var(--safe-ink)" }}>{I.checkCircle({ size: 22 })}</span>
              <span style={{ font: "600 14.5px/1.3 var(--font-display)", color: "var(--safe-ink)" }}>Fayl o'chirildi. Telefoningiz xavfsiz.</span>
            </div>
          ) : (
            <button className="btn danger block lg" onClick={() => setDeleted(true)}>{I.trash({ size: 20 })} Telefondan o'chirish</button>
          )}
          <button className="btn ghost block" onClick={() => go("apps")}>{I.bell({ size: 19 })} Do'stlarni ogohlantirish</button>
        </div>
      </div>
    </div>
  );
}

/* ─────────────── INITIAL SCAN (birinchi tekshiruv) ─────────────── */
function InitialScan({ go, openThreat }) {
  const [pct, setPct] = useState(0);
  const [phase, setPhase] = useState("scan");
  const danger = window.APPS.filter(a => !a.safe);
  const [list, setList] = useState(danger);

  useEffect(() => {
    let p = 0;
    const iv = setInterval(() => { p += 5; setPct(Math.min(p, 100)); if (p >= 100) { clearInterval(iv); setTimeout(() => setPhase("done"), 400); } }, 90);
    return () => clearInterval(iv);
  }, []);

  if (phase === "scan") {
    return (
      <div className="fade" style={{ position: "absolute", inset: 0, display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", padding: 24 }}>
        <div style={starBg(1.4)} />
        <Ring value={pct} size={190} stroke={12} color="var(--primary)" track="var(--surface-2)">
          <div style={{ textAlign: "center" }}>
            <div style={{ font: "800 40px/1 var(--font-display)", letterSpacing: "-.02em", color: "var(--ink)" }}>{pct}%</div>
          </div>
        </Ring>
        <div className="eyebrow" style={{ marginTop: 28 }}>BIRINCHI TEKSHIRUV</div>
        <h1 className="h-title" style={{ marginTop: 10, textAlign: "center" }}>Telefon tekshirilmoqda</h1>
        <p className="h-sub" style={{ marginTop: 8, textAlign: "center", maxWidth: 300 }}>Zararli APK fayllar qidirilmoqda. Bu bir necha soniya oladi.</p>
      </div>
    );
  }
  return (
    <div className="fade screen-pad" style={{ paddingTop: 28 }}>
      <div style={{ textAlign: "center", marginBottom: 20 }}>
        <div style={{ width: 80, height: 80, borderRadius: "50%", background: "var(--danger-bg)", color: "var(--danger)", display: "grid", placeItems: "center", margin: "0 auto" }}>{I.shieldAlert({ size: 40 })}</div>
        <h1 className="h-title" style={{ marginTop: 16 }}>{list.length} ta xavfli fayl topildi</h1>
        <p className="h-sub" style={{ marginTop: 8 }}>Quyidagi fayllarni o'chirish tavsiya etiladi.</p>
      </div>
      <div className="card" style={{ overflow: "hidden" }}>
        {list.map(a => (
          <div key={a.id} className="li" onClick={() => { const t = window.THREATS.find(x => x.id === a.tId); if (t) openThreat(t); }}>
            <div className="av danger">{I.file({ size: 22 })}</div>
            <div className="grow"><div className="ttl">{a.name}</div><div className="sub">{a.from} · {a.size}</div></div>
            <span className="tag danger"><span className="dot" />Xavfli</span>
          </div>
        ))}
        {list.length === 0 && (
          <div style={{ padding: 28, textAlign: "center" }}>
            <div style={{ color: "var(--safe)", display: "grid", placeItems: "center" }}>{I.checkCircle({ size: 40 })}</div>
            <div style={{ font: "600 15px/1.3 var(--font-display)", marginTop: 12 }}>Barcha xavfli fayllar o'chirildi</div>
          </div>
        )}
      </div>
      <div style={{ marginTop: 20, display: "flex", flexDirection: "column", gap: 10 }}>
        {list.length > 0 && <button className="btn danger block lg" onClick={() => setList([])}>{I.trash({ size: 20 })} Hammasini o'chirish</button>}
        <button className="btn primary block lg" onClick={() => go("dashboard")}>Asosiy ekranga o'tish</button>
      </div>
    </div>
  );
}

/* ─────────────── RUXSATLAR (so'ralgan ruxsatlar) ─────────────── */
function Permissions({ go, threat }) {
  const t = threat || window.THREATS[0];
  const P = window.PERMS;
  const Group = ({ title, items, sev }) => {
    const col = sev === "crit" ? "var(--danger)" : sev === "warn" ? "var(--warn)" : "var(--ink-3)";
    const bg = sev === "crit" ? "var(--danger-bg)" : sev === "warn" ? "var(--warn-bg)" : "var(--surface-2)";
    const ink = sev === "crit" ? "var(--danger-ink)" : sev === "warn" ? "var(--warn-ink)" : "var(--ink-2)";
    const badge = sev === "crit" ? "KRITIK" : sev === "warn" ? "DIQQAT" : "ODDIY";
    return (
      <div style={{ marginBottom: 18 }}>
        <div className="between" style={{ marginBottom: 10, padding: "0 2px" }}>
          <span className="sec-title" style={{ fontSize: 15 }}>{title}</span>
          {sev !== "normal" && <span className="tag" style={{ background: bg, color: ink, height: 26 }}><span className="dot" />{badge}</span>}
        </div>
        <div className="card" style={{ overflow: "hidden" }}>
          {items.map((p, i) => (
            <div key={i} className="li" style={{ cursor: "default" }}>
              <div style={{ width: 44, height: 44, borderRadius: "var(--r-md)", background: bg, color: col, display: "grid", placeItems: "center", flexShrink: 0 }}>{I[p.icon]({ size: 21 })}</div>
              <div className="grow">
                <div className="ttl" style={{ whiteSpace: "normal" }}>{p.t}</div>
                <div className="sub" style={{ whiteSpace: "normal" }}>{p.s}</div>
              </div>
            </div>
          ))}
        </div>
      </div>
    );
  };
  return (
    <div className="fade screen-pad">
      <div className="row" style={{ gap: 12, marginBottom: 16 }}>
        <button className="icon-btn" onClick={() => go("result")}>{I.back({ size: 20 })}</button>
        <div style={{ minWidth: 0 }}>
          <div className="eyebrow">RUXSATLAR</div>
          <h1 className="h-title" style={{ marginTop: 6, fontSize: 22, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{t.name}</h1>
        </div>
      </div>

      <div className="card" style={{ padding: 16, display: "flex", gap: 13, alignItems: "center", marginBottom: 20,
        background: "var(--danger-bg)", borderColor: "transparent" }}>
        <span style={{ color: "var(--danger)" }}>{I.shieldAlert({ size: 26 })}</span>
        <p style={{ font: "500 13.5px/1.45 var(--font-body)", color: "var(--danger-ink)", margin: 0 }}>
          Bu fayl <b>juda xavfli ruxsatlar</b> so'raydi. Ular maxfiy ma'lumotlaringizga — SMS, bank kodlari, ekran — kirishi mumkin.
        </p>
      </div>

      <Group title="Eng xavfli ruxsatlar" items={P.crit} sev="crit" />
      <Group title="E'tibor bering" items={P.warn} sev="warn" />
      <Group title="Oddiy ruxsatlar" items={P.normal} sev="normal" />

      <button className="btn danger block lg" style={{ marginTop: 4 }} onClick={() => go("result")}>{I.back({ size: 19 })} Natijaga qaytish</button>
    </div>
  );
}

Object.assign(window, { AutoScan, ScanResult, InitialScan, Permissions });
