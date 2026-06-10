/* ============================================================
   ANOR QALQON — Tweaks panel (mavzu · aksent · ekran)
   TweaksPanel (host protokoli) ichida o'z boshqaruvlarimiz.
   ============================================================ */

function Tweaks({ nav }) {
  const accents = [
    { k: "anor", l: "Anor", c: "#c52a3e" },
    { k: "feruz", l: "Feruz", c: "#1f9489" },
    { k: "zafaron", l: "Za'faron", c: "#c5871f" },
  ];
  const screens = [
    { value: "language", label: "Til tanlash" }, { value: "splash", label: "Splash" },
    { value: "onboarding", label: "Tanishtiruv" }, { value: "initialscan", label: "Birinchi tekshiruv" },
    { value: "dashboard", label: "Asosiy" }, { value: "apps", label: "Skaner" },
    { value: "autoscan", label: "Avto-ogohlantirish" }, { value: "result", label: "Natija (xavfli)" },
    { value: "stats", label: "Statistika" }, { value: "settings", label: "Sozlamalar" },
    { value: "quarantine", label: "Karantin" }, { value: "protection", label: "Himoya holati" },
    { value: "permissions", label: "Ruxsatlar" },
    { value: "about", label: "Loyiha haqida" }, { value: "report", label: "Muammo xabari" },
  ];

  return (
    <TweaksPanel title="Tweaks">
      <TweakSection label="Ko'rinish" />
      <TweakRadio label="Mavzu" value={nav.theme}
        options={[{ value: "light", label: "Kunduzgi" }, { value: "dark", label: "Tungi" }]}
        onChange={nav.setTheme} />

      <div style={{ padding: "10px 2px 4px" }}>
        <div style={{ font: "600 12.5px/1 var(--tw-label, inherit)", color: "#8a8f98", marginBottom: 9 }}>Asosiy rang (milliy)</div>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(3,1fr)", gap: 8 }}>
          {accents.map(a => (
            <button key={a.k} onClick={() => nav.setAccent(a.k)}
              style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 7, padding: "11px 6px", cursor: "pointer",
                borderRadius: 12, border: nav.accent === a.k ? "1.5px solid " + a.c : "1px solid rgba(140,140,150,.3)",
                background: nav.accent === a.k ? a.c + "18" : "transparent" }}>
              <span style={{ width: 26, height: 26, borderRadius: "50%", background: a.c,
                boxShadow: nav.accent === a.k ? `0 0 0 2px #fff, 0 0 0 4px ${a.c}` : "none" }} />
              <span style={{ font: "600 12px/1 sans-serif", color: "#3a3f48" }}>{a.l}</span>
            </button>
          ))}
        </div>
      </div>

      <TweakSection label="Ekranlar" />
      <TweakSelect label="Ochish" value={nav.screen} options={screens} onChange={nav.go} />
    </TweaksPanel>
  );
}

window.Tweaks = Tweaks;
