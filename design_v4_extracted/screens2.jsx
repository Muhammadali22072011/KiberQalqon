/* ============================================================
   ANOR QALQON — Sozlamalar · Statistika · Karantin ·
   Himoya holati · Loyiha haqida · Muammo
   ============================================================ */

function Row({ icon, t, s, right, onClick, tone = "primary" }) {
  const bg = tone === "danger" ? "var(--danger-bg)" : tone === "safe" ? "var(--safe-bg)" : "var(--primary-soft)";
  const col = tone === "danger" ? "var(--danger)" : tone === "safe" ? "var(--safe)" : "var(--primary)";
  return (
    <div className="li" onClick={onClick} style={{ cursor: onClick ? "pointer" : "default" }}>
      <div style={{ width: 44, height: 44, borderRadius: "var(--r-md)", background: bg, color: col, display: "grid", placeItems: "center", flexShrink: 0 }}>{I[icon]({ size: 21 })}</div>
      <div className="grow">
        <div className="ttl" style={{ whiteSpace: "normal" }}>{t}</div>
        {s && <div className="sub" style={{ whiteSpace: "normal" }}>{s}</div>}
      </div>
      {right}
    </div>
  );
}
function Toggle({ on, set }) {
  return <button className={"toggle" + (on ? " on" : "")} onClick={() => set(!on)} />;
}
function SecTitle({ children }) {
  return <div className="eyebrow" style={{ margin: "22px 4px 9px" }}>{children}</div>;
}

/* ─────────────── SOZLAMALAR ─────────────── */
function Settings({ go, theme, setTheme, accent, setAccent }) {
  const [tg, setTg] = useState({ auto: true, del: true, phish: false, bg: true, cloud: false });
  const set = (k) => (v) => setTg(s => ({ ...s, [k]: v }));
  const accents = [["anor", "Anor", "#c52a3e"], ["feruz", "Feruz", "#1f9489"], ["zafaron", "Za'faron", "#c5871f"]];
  return (
    <div className="fade screen-pad">
      <div className="eyebrow">SOZLAMALAR</div>
      <h1 className="h-title" style={{ marginTop: 8, marginBottom: 16 }}>Boshqaruv</h1>

      {/* profile */}
      <div className="card" style={{ padding: 16, display: "flex", gap: 14, alignItems: "center", overflow: "hidden" }}>
        <div style={starBg(2.2)} />
        <Logo size={50} radius={14} />
        <div className="grow" style={{ position: "relative" }}>
          <div style={{ font: "700 16px/1.1 var(--font-display)" }}>Anor Qalqon</div>
          <div className="mono" style={{ fontSize: 10.5, letterSpacing: ".08em", color: "var(--ink-3)", marginTop: 5 }}>VERSIYA 7.8 · NAVOIY</div>
        </div>
        <span className="tag safe" style={{ position: "relative" }}><span className="dot" />Faol</span>
      </div>

      <SecTitle>HIMOYA</SecTitle>
      <div className="card" style={{ overflow: "hidden" }}>
        <Row icon="scan" t="Avtomatik tekshirish" s="Har 15 daqiqada papkalarni tekshiradi" right={<Toggle on={tg.auto} set={set("auto")} />} />
        <Row icon="trash" t="Xavfli faylni o'zi o'chirsin" s="Topilgan zararli fayl 5 soniyada o'chiriladi" right={<Toggle on={tg.del} set={set("del")} />} />
        <Row icon="message" t="Soxta SMS himoyasi" s="Kod so'ragan shubhali xabarlarni yashiradi" right={<Toggle on={tg.phish} set={set("phish")} />} />
        <Row icon="refresh" t="Fonda ishlash" s="Telefon o'chsa ham himoya qayta yoqiladi" right={<Toggle on={tg.bg} set={set("bg")} />} />
      </div>

      <SecTitle>KO'RINISH</SecTitle>
      <div className="card" style={{ padding: 16 }}>
        <div className="between">
          <div className="row" style={{ gap: 13 }}>
            <div style={{ width: 44, height: 44, borderRadius: "var(--r-md)", background: "var(--primary-soft)", color: "var(--primary)", display: "grid", placeItems: "center" }}>{theme === "dark" ? I.moon({ size: 21 }) : I.sun({ size: 21 })}</div>
            <div><div className="ttl">Mavzu</div><div className="sub">Kunduzgi yoki tungi</div></div>
          </div>
          <div style={{ display: "flex", gap: 4, background: "var(--surface-2)", borderRadius: "var(--r-pill)", padding: 4 }}>
            {[["light", "sun"], ["dark", "moon"]].map(([k, ic]) => (
              <button key={k} onClick={() => setTheme(k)} style={{ width: 44, height: 36, borderRadius: "var(--r-pill)", border: "none", cursor: "pointer",
                display: "grid", placeItems: "center", color: theme === k ? "var(--on-primary)" : "var(--ink-3)",
                background: theme === k ? "var(--primary)" : "transparent" }}>{I[ic]({ size: 18 })}</button>
            ))}
          </div>
        </div>
        <div style={{ height: 1, background: "var(--hairline)", margin: "16px 0" }} />
        <div className="ttl" style={{ marginBottom: 4 }}>Asosiy rang</div>
        <div className="sub" style={{ marginBottom: 14 }}>Milliy rang uslubini tanlang</div>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(3,1fr)", gap: 10 }}>
          {accents.map(([k, l, c]) => (
            <button key={k} onClick={() => setAccent(k)} style={{ padding: "14px 8px", borderRadius: "var(--r-md)", cursor: "pointer",
              border: accent === k ? "1.5px solid var(--primary)" : "1px solid var(--hairline)",
              background: accent === k ? "var(--surface-2)" : "transparent", display: "flex", flexDirection: "column", alignItems: "center", gap: 9 }}>
              <span style={{ width: 30, height: 30, borderRadius: "50%", background: c, boxShadow: accent === k ? `0 0 0 3px var(--surface), 0 0 0 5px ${c}` : "none" }} />
              <span style={{ font: "600 12.5px/1 var(--font-display)", color: "var(--ink)" }}>{l}</span>
            </button>
          ))}
        </div>
      </div>

      <SecTitle>TIL</SecTitle>
      <div className="card" style={{ overflow: "hidden" }}>
        <Row icon="globe" t="O'zbekcha" right={<span style={{ color: "var(--primary)" }}>{I.checkCircle({ size: 22 })}</span>} onClick={() => {}} />
        <Row icon="globe" t="Русский" tone="primary" right={<span style={{ color: "var(--ink-3)" }}>{I.chevron({ size: 20 })}</span>} onClick={() => go("language")} />
      </div>

      <SecTitle>HAQIDA</SecTitle>
      <div className="card" style={{ overflow: "hidden" }}>
        <Row icon="heart" t="Loyiha haqida" right={I.chevron({ size: 20 })} onClick={() => go("about")} />
        <Row icon="help" t="Yordam markazi" right={I.chevron({ size: 20 })} onClick={() => go("report")} />
        <Row icon="alert" t="Muammo haqida xabar berish" right={I.chevron({ size: 20 })} onClick={() => go("report")} />
      </div>

      <div className="mono" style={{ textAlign: "center", fontSize: 10.5, letterSpacing: ".06em", color: "var(--ink-3)", marginTop: 26, lineHeight: 1.7 }}>
        ANOR QALQON v7.8 · BUILD 78<br />© 2026 · Muhammadali · Navoiy
      </div>
    </div>
  );
}

/* ─────────────── STATISTIKA ─────────────── */
function Stats({ go, openThreat }) {
  const max = Math.max(...window.WEEK.map(d => d.n));
  const fam = [
    { t: "Bank o'g'irlash", v: 4, c: "var(--danger)" },
    { t: "Yashirin yuklash", v: 1, c: "var(--danger)" },
    { t: "Soxta SMS", v: 0, c: "var(--warn)" },
  ];
  return (
    <div className="fade screen-pad">
      <div className="eyebrow">STATISTIKA</div>
      <h1 className="h-title" style={{ marginTop: 8, marginBottom: 16 }}>So'nggi 7 kun</h1>

      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
        <div className="card" style={{ padding: 16 }}>
          <div style={{ font: "800 34px/1 var(--font-display)", letterSpacing: "-.03em", color: "var(--ink)" }}>247</div>
          <div style={{ font: "500 12.5px/1.3 var(--font-body)", color: "var(--ink-3)", marginTop: 8 }}>Tekshirilgan fayl</div>
        </div>
        <div className="card" style={{ padding: 16 }}>
          <div style={{ font: "800 34px/1 var(--font-display)", letterSpacing: "-.03em", color: "var(--danger)" }}>5</div>
          <div style={{ font: "500 12.5px/1.3 var(--font-body)", color: "var(--ink-3)", marginTop: 8 }}>Topildi va o'chirildi</div>
        </div>
      </div>

      {/* chart */}
      <div className="card" style={{ padding: 16, marginTop: 12 }}>
        <div className="between" style={{ marginBottom: 16 }}>
          <span className="sec-title" style={{ fontSize: 15 }}>Kunlik tekshiruvlar</span>
          <span className="tag outline">Hafta</span>
        </div>
        <div style={{ display: "flex", alignItems: "flex-end", gap: 8, height: 130 }}>
          {window.WEEK.map((d, i) => (
            <div key={i} style={{ flex: 1, display: "flex", flexDirection: "column", alignItems: "center", gap: 8 }}>
              <div style={{ position: "relative", width: "100%", height: 104, display: "flex", alignItems: "flex-end" }}>
                {d.bad > 0 && <span style={{ position: "absolute", top: 0, left: "50%", transform: "translateX(-50%)", width: 18, height: 18, borderRadius: "50%", background: "var(--danger)", color: "#fff", font: "700 10px/18px var(--font-display)", textAlign: "center" }}>{d.bad}</span>}
                <div style={{ width: "100%", height: `${(d.n / max) * 78 + 12}%`, borderRadius: "7px 7px 3px 3px",
                  background: d.bad > 0 ? "linear-gradient(var(--danger), var(--danger))" : "linear-gradient(var(--primary), var(--primary-2))" }} />
              </div>
              <span className="mono" style={{ fontSize: 10, color: "var(--ink-3)" }}>{d.d}</span>
            </div>
          ))}
        </div>
      </div>

      {/* families */}
      <h2 className="sec-title" style={{ fontSize: 16, marginTop: 22, marginBottom: 12 }}>Qanday xavflar topildi</h2>
      <div className="card" style={{ padding: 16 }}>
        {fam.map((f, i) => (
          <div key={i} style={{ marginBottom: i < fam.length - 1 ? 14 : 0 }}>
            <div className="between" style={{ marginBottom: 7 }}>
              <span style={{ font: "600 13.5px/1 var(--font-display)" }}>{f.t}</span>
              <span className="mono" style={{ fontSize: 12, color: "var(--ink-3)" }}>{f.v} / 5</span>
            </div>
            <div style={{ height: 9, borderRadius: 5, background: "var(--surface-2)", overflow: "hidden" }}>
              <div style={{ width: `${(f.v / 5) * 100}%`, height: "100%", borderRadius: 5, background: f.c }} />
            </div>
          </div>
        ))}
      </div>

      {/* samples */}
      <h2 className="sec-title" style={{ fontSize: 16, marginTop: 22, marginBottom: 10 }}>Topilgan fayllar</h2>
      <div className="card" style={{ overflow: "hidden" }}>
        {window.APPS.filter(a => !a.safe).map(a => (
          <div key={a.id} className="li" onClick={() => { const t = window.THREATS.find(x => x.id === a.tId); if (t) openThreat(t); }}>
            <div className="av danger" style={{ width: 42, height: 42 }}>{I.file({ size: 20 })}</div>
            <div className="grow"><div className="ttl">{a.name}</div><div className="sub">{a.from} · {a.size}</div></div>
            <span className="tag danger"><span className="dot" />Xavfli</span>
          </div>
        ))}
      </div>
      <button className="btn ghost block" style={{ marginTop: 16 }} onClick={() => go("dashboard")}>{I.refresh({ size: 19 })} Hisobotni yangilash</button>
    </div>
  );
}

/* ─────────────── KARANTIN ─────────────── */
function Quarantine({ go }) {
  const [items, setItems] = useState(window.QUARANTINE);
  return (
    <div className="fade screen-pad">
      <div className="row" style={{ gap: 12, marginBottom: 16 }}>
        <button className="icon-btn" onClick={() => go("dashboard")}>{I.back({ size: 20 })}</button>
        <div>
          <div className="eyebrow">KARANTIN</div>
          <h1 className="h-title" style={{ marginTop: 6, fontSize: 24 }}>O'chirilgan fayllar</h1>
        </div>
      </div>
      <p className="h-sub" style={{ marginBottom: 16 }}>Bu fayllar xavfli edi va telefoningizdan o'chirildi. Ular endi zarar yetkaza olmaydi.</p>
      {items.length === 0 ? (
        <div className="card" style={{ padding: 36, textAlign: "center" }}>
          <div style={{ color: "var(--safe)", display: "grid", placeItems: "center" }}>{I.checkCircle({ size: 44 })}</div>
          <div style={{ font: "600 15px/1.3 var(--font-display)", marginTop: 14 }}>Karantin bo'sh</div>
          <div className="h-sub" style={{ marginTop: 6 }}>Hamma narsa tozalandi.</div>
        </div>
      ) : (
        <div className="card" style={{ overflow: "hidden" }}>
          {items.map(q => (
            <div key={q.id} className="li" style={{ cursor: "default" }}>
              <div className="av danger">{I.trash({ size: 20 })}</div>
              <div className="grow"><div className="ttl">{q.name}</div><div className="sub">{q.from} · {q.time}</div></div>
              <span className="tag soft">O'chirildi</span>
            </div>
          ))}
        </div>
      )}
      {items.length > 0 && (
        <button className="btn ghost block" style={{ marginTop: 16 }} onClick={() => setItems([])}>{I.trash({ size: 19 })} Karantinni butunlay tozalash</button>
      )}
    </div>
  );
}

/* ─────────────── HIMOYA HOLATI ─────────────── */
function Protection({ go }) {
  const items = window.PROT_STATUS;
  const okCount = items.filter(i => i.ok).length;
  return (
    <div className="fade screen-pad">
      <div className="row" style={{ gap: 12, marginBottom: 18 }}>
        <button className="icon-btn" onClick={() => go("dashboard")}>{I.back({ size: 20 })}</button>
        <div>
          <div className="eyebrow">HIMOYA HOLATI</div>
          <h1 className="h-title" style={{ marginTop: 6, fontSize: 24 }}>Ruxsatlar</h1>
        </div>
      </div>

      <div className="card" style={{ padding: 18, display: "flex", flexDirection: "column", alignItems: "center", textAlign: "center", overflow: "hidden", marginBottom: 18 }}>
        <div style={starBg(2.2)} />
        <Ring value={(okCount / items.length) * 100} size={120} stroke={11} color={okCount === items.length ? "var(--safe)" : "var(--warn)"} track="var(--surface-2)">
          <div style={{ color: okCount === items.length ? "var(--safe)" : "var(--warn)" }}>{I.shieldCheck({ size: 38 })}</div>
        </Ring>
        <div style={{ position: "relative", font: "700 18px/1.2 var(--font-display)", marginTop: 14 }}>{okCount} / {items.length} ruxsat berilgan</div>
        <div className="h-sub" style={{ position: "relative", marginTop: 6 }}>{okCount === items.length ? "Himoya to'liq yoqilgan" : "Bitta ruxsatni yoqing — himoya kuchayadi"}</div>
      </div>

      <div className="card" style={{ overflow: "hidden" }}>
        {items.map((p, i) => (
          <Row key={i} icon={p.icon} t={p.t} s={p.s} tone={p.ok ? "safe" : "warn"}
            right={p.ok
              ? <span className="tag safe"><span className="dot" />Yoniq</span>
              : <button className="btn sm primary" style={{ height: 36 }}>Yoqish</button>} />
        ))}
      </div>
    </div>
  );
}

/* ─────────────── LOYIHA HAQIDA ─────────────── */
function About({ go }) {
  const feats = [
    { icon: "scan", t: "Avtomatik tekshirish", s: "Har 15 daqiqada yangi fayllarni tekshiradi" },
    { icon: "trash", t: "Xavfni o'zi o'chiradi", s: "Zararli faylni topib, darhol yo'q qiladi" },
    { icon: "message", t: "Soxta SMS himoyasi", s: "Bank kodi so'ragan aldamchi xabarlarni yashiradi" },
    { icon: "globe", t: "Ikki til", s: "O'zbekcha va Ruscha" },
  ];
  return (
    <div className="fade screen-pad">
      <div className="row" style={{ gap: 12, marginBottom: 20 }}>
        <button className="icon-btn" onClick={() => go("settings")}>{I.back({ size: 20 })}</button>
        <div>
          <div className="eyebrow">LOYIHA HAQIDA</div>
          <h1 className="h-title" style={{ marginTop: 6, fontSize: 24 }}>Anor Qalqon</h1>
        </div>
      </div>

      <div className="card" style={{ padding: 22, textAlign: "center", overflow: "hidden", marginBottom: 18 }}>
        <div style={starBg(2.2)} />
        <div style={{ position: "relative" }}>
          <LogoDisc size={92} />
          <div style={{ font: "800 22px/1 var(--font-display)", letterSpacing: "-.02em", marginTop: 16 }}>ANOR QALQON</div>
          <div className="mono" style={{ fontSize: 11, letterSpacing: ".16em", color: "var(--ink-3)", marginTop: 8 }}>MILLIY KIBER HIMOYA</div>
        </div>
      </div>

      <p className="h-sub" style={{ marginBottom: 20 }}>Anor Qalqon — O'zbekiston uchun yaratilgan zamonaviy himoya ilovasi. Telefoningizni zararli APK fayllar va aldamchi hujumlardan asraydi. Hamma narsa oddiy o'zbek tilida tushuntiriladi.</p>

      <h2 className="sec-title" style={{ fontSize: 16, marginBottom: 10 }}>Imkoniyatlar</h2>
      <div className="card" style={{ overflow: "hidden", marginBottom: 20 }}>
        {feats.map((f, i) => <Row key={i} icon={f.icon} t={f.t} s={f.s} />)}
      </div>

      <h2 className="sec-title" style={{ fontSize: 16, marginBottom: 10 }}>Muallif</h2>
      <div className="card" style={{ padding: 16, display: "flex", gap: 13, alignItems: "center" }}>
        <div style={{ width: 48, height: 48, borderRadius: "50%", background: "var(--primary-soft)", color: "var(--primary)", display: "grid", placeItems: "center" }}>{I.user({ size: 24 })}</div>
        <div>
          <div className="ttl">Muhammadali</div>
          <div className="sub">Navoiy · O'zbekiston</div>
        </div>
      </div>

      <div className="mono" style={{ textAlign: "center", fontSize: 10.5, color: "var(--ink-3)", marginTop: 22 }}>© 2026 · ANOR QALQON · v7.8</div>
    </div>
  );
}

/* ─────────────── MUAMMO HAQIDA XABAR ─────────────── */
function Report({ go }) {
  const [txt, setTxt] = useState("");
  const [sent, setSent] = useState(false);
  return (
    <div className="fade screen-pad">
      <div className="row" style={{ gap: 12, marginBottom: 18 }}>
        <button className="icon-btn" onClick={() => go("settings")}>{I.back({ size: 20 })}</button>
        <div>
          <div className="eyebrow">YORDAM</div>
          <h1 className="h-title" style={{ marginTop: 6, fontSize: 24 }}>Muammo haqida xabar</h1>
        </div>
      </div>

      {sent ? (
        <div className="card" style={{ padding: 36, textAlign: "center" }}>
          <div style={{ color: "var(--safe)", display: "grid", placeItems: "center" }}>{I.checkCircle({ size: 48 })}</div>
          <div style={{ font: "700 18px/1.3 var(--font-display)", marginTop: 16 }}>Rahmat!</div>
          <p className="h-sub" style={{ marginTop: 8 }}>Muammoingiz dasturchiga yuborildi. Tez orada hal qilamiz.</p>
          <button className="btn primary block lg" style={{ marginTop: 20 }} onClick={() => go("settings")}>Yopish</button>
        </div>
      ) : (
        <>
          <p className="h-sub" style={{ marginBottom: 16 }}>Xato yoki muammoni shu yerda yozing — to'g'ridan-to'g'ri dasturchiga boradi.</p>
          <textarea value={txt} onChange={e => setTxt(e.target.value)} placeholder="Nima bo'ldi? Muammoni shu yerda yozing…"
            style={{ width: "100%", minHeight: 140, padding: 16, borderRadius: "var(--r-lg)", border: "1px solid var(--hairline-2)",
              background: "var(--surface)", color: "var(--ink)", font: "400 15px/1.5 var(--font-body)", resize: "none", outline: "none" }} />
          <div className="card sunken" style={{ padding: 15, marginTop: 14 }}>
            <div className="row" style={{ gap: 9, marginBottom: 8 }}>
              <span style={{ color: "var(--ink-3)" }}>{I.lock({ size: 17 })}</span>
              <span style={{ font: "600 13px/1 var(--font-display)", color: "var(--ink-2)" }}>Maxfiyligingiz himoyalangan</span>
            </div>
            <p style={{ font: "400 12.5px/1.5 var(--font-body)", color: "var(--ink-3)", margin: 0 }}>Shaxsiy ma'lumotlaringiz (ism, raqam, boshqa ilovalar) yuborilmaydi.</p>
          </div>
          <button className="btn primary block lg" style={{ marginTop: 18, opacity: txt.trim() ? 1 : .5 }} disabled={!txt.trim()} onClick={() => setSent(true)}>
            {I.message({ size: 19 })} Yuborish
          </button>
        </>
      )}
    </div>
  );
}

Object.assign(window, { Settings, Stats, Quarantine, Protection, About, Report });
