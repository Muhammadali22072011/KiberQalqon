/* eslint-disable */
/* ============================================================
   KiberQalqon — Splash + Onboarding (3 slides)
   ============================================================ */

const Splash = ({ onContinue }) => {
  React.useEffect(() => {
    const t = setTimeout(onContinue, 2400);
    return () => clearTimeout(t);
  }, []);
  return (
    <div className="fade-in" style={{
      position: "absolute", inset: 0,
      display: "flex", flexDirection: "column",
      alignItems: "center", justifyContent: "center",
      background: "linear-gradient(160deg, var(--primary) 0%, var(--primary-2) 100%)",
      color: "#fff",
      overflow: "hidden",
    }}>
      {/* tiled stars in background */}
      <div style={{
        position: "absolute", inset: 0,
        backgroundImage: `url("data:image/svg+xml;utf8,${encodeURIComponent(
          `<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 80 80' width='80' height='80'>
            <g fill='none' stroke='white' stroke-width='1.2' opacity='0.18'>
              <path d='M40 8 L48 32 L72 40 L48 48 L40 72 L32 48 L8 40 L32 32 Z'/>
              <path d='M40 18 L46 34 L62 40 L46 46 L40 62 L34 46 L18 40 L34 34 Z'/>
            </g>
          </svg>`
        )}")`,
        backgroundSize: "80px 80px",
        opacity: 0.9,
      }}/>
      {/* pulsing rings */}
      <div style={{
        position: "relative",
        width: 160, height: 160,
        display: "grid", placeItems: "center",
      }}>
        <div className="pulse-ring" style={{
          background: "rgba(255,255,255,0.2)",
        }} />
        <div className="pulse-ring" style={{
          background: "rgba(255,255,255,0.2)",
          animationDelay: "0.7s",
        }} />
        <div style={{
          width: 110, height: 110,
          borderRadius: "50%",
          background: "rgba(255,255,255,0.15)",
          backdropFilter: "blur(6px)",
          display: "grid", placeItems: "center",
          border: "1px solid rgba(255,255,255,0.3)",
          position: "relative", zIndex: 2,
        }}>
          <svg width="68" height="68" viewBox="0 0 100 100">
            <path d="M50 6 L86 18 V46 C86 70 70 86 50 94 C30 86 14 70 14 46 V18 Z" fill="rgba(255,255,255,0.95)"/>
            <g transform="translate(50 50)" fill="var(--primary)">
              <path d="M0 -28 L8 -8 L28 0 L8 8 L0 28 L-8 8 L-28 0 L-8 -8 Z"/>
            </g>
          </svg>
        </div>
      </div>
      <div style={{
        marginTop: 38,
        font: "800 38px/1 var(--font-display)",
        letterSpacing: "-0.02em",
        position: "relative", zIndex: 2,
      }}>
        KiberQalqon
      </div>
      <div style={{
        marginTop: 10,
        font: "500 13px/1 var(--font-mono)",
        letterSpacing: "0.24em",
        textTransform: "uppercase",
        opacity: 0.85,
        position: "relative", zIndex: 2,
      }}>
        Telefon himoyasi · v 7.5
      </div>
      <div style={{
        position: "absolute", bottom: 70,
        font: "500 12px/1 var(--font-body)",
        opacity: 0.75,
      }}>
        Zararli APK fayllardan himoyalanish
      </div>
      <div style={{
        position: "absolute", bottom: 38,
        display: "flex", gap: 4,
      }}>
        {[0,1,2].map(i => (
          <div key={i} style={{
            width: 6, height: 6, borderRadius: "50%",
            background: "rgba(255,255,255,0.7)",
            animation: `loadDot 1.2s ${i*0.15}s infinite ease-in-out`,
          }}/>
        ))}
      </div>
      <style>{`@keyframes loadDot { 0%,100%{opacity:.3;transform:scale(.8)} 50%{opacity:1;transform:scale(1.1)} }`}</style>
    </div>
  );
};

const ONB_SLIDES = [
  {
    eyebrow: "01 · BOSHLASH",
    title: "Avtomatik himoya — 24/7",
    body: "KiberQalqon Telegram, Yuklab olishlar va boshqa papkalarni har 15 daqiqada tekshirib turadi. Zararli APK fayl topilsa — darhol bloklanadi.",
    visual: "auto",
  },
  {
    eyebrow: "02 · ANIQLASH",
    title: "5 ta zararli oilani aniqlaymiz",
    body: "Ajina.Banker, RoundRift va boshqa Markaziy Osiyo bank troyanlarining yangi avlodlari sizning telefoningizgacha yetib bormaydi.",
    visual: "threats",
  },
  {
    eyebrow: "03 · NAZORAT",
    title: "Sodda til, kuchli himoya",
    body: "O'zbek tilida tushuntirilgan natijalar. Hech qachon shifrlangan kalitlar va texnik atamalarda yo'qolib qolmaysiz.",
    visual: "control",
  },
];

const OnbVisual = ({ kind }) => {
  if (kind === "auto") {
    return (
      <div style={{ position: "relative", width: 220, height: 220 }}>
        <ProtectionMeter value={92} size={220} label="Faol" />
      </div>
    );
  }
  if (kind === "threats") {
    return (
      <div style={{
        display: "grid", gridTemplateColumns: "repeat(2, 1fr)", gap: 10,
        width: 240,
      }}>
        {[
          {n: "Ajina.Banker", c: "danger"},
          {n: "RoundRift", c: "danger"},
          {n: "SMS Stealer", c: "warn"},
          {n: "Phish Overlay", c: "warn"},
        ].map((t, i) => (
          <div key={i} className="card" style={{ padding: "12px 12px", border: "1px solid var(--hairline)" }}>
            <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <div style={{
                width: 28, height: 28, borderRadius: 8,
                background: `var(--${t.c}-bg)`, color: `var(--${t.c}-ink)`,
                display: "grid", placeItems: "center",
              }}>
                <I.bug size={16}/>
              </div>
              <span className={`sev ${t.c === "danger" ? "crit" : "high"}`}>{t.c === "danger" ? "KRIT" : "YUQORI"}</span>
            </div>
            <div style={{
              marginTop: 8, font: "600 12.5px/1.2 var(--font-display)",
              color: "var(--ink)",
            }}>{t.n}</div>
          </div>
        ))}
      </div>
    );
  }
  // control
  return (
    <div style={{
      width: 240, padding: "16px 16px",
      borderRadius: 18, background: "var(--bg-elev)",
      border: "1px solid var(--hairline)",
      boxShadow: "var(--shadow-1)",
    }}>
      <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 10 }}>
        <div style={{ color: "var(--danger)" }}><I.alert size={20}/></div>
        <div style={{
          font: "700 14px/1.1 var(--font-display)", color: "var(--ink)",
          flex: 1,
        }}>Xavfli APK aniqlandi</div>
      </div>
      <div style={{ font: "500 12px/1.45 var(--font-body)", color: "var(--ink-2)" }}>
        Fayl <b>RASMLAR (18).apk</b> Telegram orqali yuklandi.<br/>
        SMS o'qish ruxsatini so'raydi.
      </div>
      <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
        <button className="btn primary" style={{ height: 40, padding: "0 14px", fontSize: 13, flex: 1 }}>O'chirish</button>
        <button className="btn ghost" style={{ height: 40, padding: "0 14px", fontSize: 13 }}>Batafsil</button>
      </div>
    </div>
  );
};

const Onboarding = ({ onDone }) => {
  const [step, setStep] = React.useState(0);
  const slide = ONB_SLIDES[step];
  const last = step === ONB_SLIDES.length - 1;

  return (
    <div className="fade-in" style={{
      position: "absolute", inset: 0,
      display: "flex", flexDirection: "column",
      background: "var(--bg)",
    }}>
      {/* skip */}
      <div style={{
        display: "flex", justifyContent: "flex-end",
        padding: "8px 16px 0",
      }}>
        <button className="btn" style={{
          height: 36, padding: "0 12px", fontSize: 13,
          background: "transparent", color: "var(--ink-3)",
        }} onClick={onDone}>O'tkazib yuborish</button>
      </div>

      {/* visual */}
      <div style={{
        flex: 1, display: "flex", alignItems: "center", justifyContent: "center",
        padding: "10px 24px",
        position: "relative",
      }}>
        <div style={{
          position: "absolute", inset: 0,
          backgroundImage: `url("data:image/svg+xml;utf8,${encodeURIComponent(
            `<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 80 80' width='80' height='80'>
              <g fill='none' stroke='currentColor' stroke-width='1' opacity='0.08'>
                <path d='M40 8 L48 32 L72 40 L48 48 L40 72 L32 48 L8 40 L32 32 Z'/>
              </g></svg>`
          )}")`,
          color: "var(--primary)",
          backgroundSize: "80px 80px",
          opacity: 0.8,
        }} />
        <div className="fade-in" key={step}><OnbVisual kind={slide.visual}/></div>
      </div>

      {/* text */}
      <div style={{ padding: "0 28px" }}>
        <div className="h-eyebrow">{slide.eyebrow}</div>
        <h1 className="fade-in" key={"t"+step} style={{
          font: "700 28px/1.18 var(--font-display)",
          letterSpacing: "-0.02em",
          color: "var(--ink)",
          margin: "8px 0 12px",
        }}>{slide.title}</h1>
        <p className="fade-in" key={"b"+step} style={{
          font: "400 15px/1.5 var(--font-body)",
          color: "var(--ink-2)",
          margin: 0,
        }}>{slide.body}</p>
      </div>

      {/* footer */}
      <div style={{ padding: "24px 24px 30px", display: "flex", flexDirection: "column", gap: 18 }}>
        <div className="pager-dots">
          {ONB_SLIDES.map((_, i) =>
            <span key={i} className={i === step ? "on" : ""}/>
          )}
        </div>
        <button className="btn primary block" onClick={() => {
          if (last) onDone();
          else setStep(step + 1);
        }}>
          {last ? "Boshlash · Himoyani yoqish" : "Davom etish"}
          <I.chevR size={16}/>
        </button>
      </div>
    </div>
  );
};

Object.assign(window, { Splash, Onboarding });
