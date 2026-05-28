/* eslint-disable */
/* ============================================================
   KiberQalqon — Main app shell with screen routing
   ============================================================ */

const KQ_DEFAULTS = /*EDITMODE-BEGIN*/{
  "theme": "light",
  "accent": "turquoise",
  "lang": "uz",
  "showAutoScan": false,
  "protection": 92,
  "patternIntensity": 0.10
}/*EDITMODE-END*/;

/* ── Status bar (custom Android-style) ───────────────────── */
const StatusBar = ({ dark }) => {
  const c = dark ? "#fff" : "var(--ink)";
  return (
    <div className="sbar" style={{ color: c }}>
      <div style={{ font: "700 14px/1 var(--font-display)" }}>9:41</div>
      <div className="punch"/>
      <div className="right" style={{ color: c }}>
        <I.wifi size={14}/>
        <I.battery size={22}/>
      </div>
    </div>
  );
};

/* ── Bottom nav ──────────────────────────────────────────── */
const BottomNav = ({ active, setActive }) => {
  const items = [
    { id: "dash",     l: "Asosiy",     ic: <I.shield size={20}/> },
    { id: "apk",      l: "Skaner",     ic: <I.scan size={20}/> },
    { id: "stats",    l: "Statistika", ic: <I.stats size={20}/> },
    { id: "settings", l: "Sozlamalar", ic: <I.settings size={20}/> },
  ];
  return (
    <div className="bnav">
      {items.map(it => (
        <button key={it.id}
          className={`item ${active === it.id ? "active" : ""}`}
          onClick={() => setActive(it.id)}>
          <span className="pill">{it.ic}</span>
          <span>{it.l}</span>
        </button>
      ))}
    </div>
  );
};

/* ── Phone shell ─────────────────────────────────────────── */
function Phone({ children, dark, hideStatus, hideNav }) {
  return (
    <div className="phone" data-frame>
      {!hideStatus && <StatusBar dark={dark} />}
      <div className="screen">{children}</div>
      {!hideNav && <div className="gnav"/>}
    </div>
  );
}

/* ── App router ──────────────────────────────────────────── */
function App() {
  const [tweaks, setTweak] = useTweaks(KQ_DEFAULTS);
  const [screen, setScreen] = React.useState("splash"); // splash → onboarding → dash → ...
  const [param, setParam]   = React.useState(null);

  // sync theme + accent
  React.useEffect(() => {
    document.documentElement.dataset.theme  = tweaks.theme || "light";
    document.documentElement.dataset.accent = tweaks.accent || "turquoise";
  }, [tweaks.theme, tweaks.accent]);

  // tweaks-driven auto-scan trigger
  React.useEffect(() => {
    if (tweaks.showAutoScan) setScreen("alert");
  }, [tweaks.showAutoScan]);

  const goto = (id, p = null) => { setParam(p); setScreen(id); };

  // map screen → which device chrome to show
  const chrome = (() => {
    if (screen === "splash") return { hideStatus: true, hideNav: true, dark: true, hideBottom: true };
    if (screen === "onboard") return { hideBottom: true };
    if (screen === "alert") return { dark: true, hideBottom: true };
    if (screen === "result") return { hideBottom: true };
    return {};
  })();

  // which tab id corresponds to current screen for bottom nav highlight
  const tabId = ({ dash: "dash", apk: "apk", stats: "stats", settings: "settings", result: "apk" })[screen];

  let body = null;
  switch (screen) {
    case "splash":    body = <Splash onContinue={() => goto("onboard")} />; break;
    case "onboard":   body = <Onboarding onDone={() => goto("dash")} />; break;
    case "dash":      body = <Dashboard goto={goto} theme={tweaks.theme} lang={tweaks.lang} />; break;
    case "apk":       body = <ApkList goto={goto} />; break;
    case "result":    body = <ScanResult goto={goto} sampleId={param} />; break;
    case "alert":     body = <AutoScanAlert goto={(id, p) => { setTweak("showAutoScan", false); goto(id, p); }} sampleId={param || "rasmlar18"} />; break;
    case "settings":  body = <SettingsScreen goto={goto} tweaks={tweaks} setTweak={setTweak} />; break;
    case "stats":     body = <StatsScreen goto={goto} />; break;
    default:          body = <Dashboard goto={goto} />;
  }

  return (
    <div className="stage">
      <div className="phone">
        {!chrome.hideStatus && <StatusBar dark={chrome.dark} />}
        <div className="screen">
          {body}
        </div>
        {!chrome.hideBottom && (
          <BottomNav active={tabId} setActive={(id) => goto(id)} />
        )}
        <div className="gnav"/>
      </div>

      {/* Tweaks panel */}
      <TweaksPanel title="Tweaks · KiberQalqon">
        <TweakSection label="Ko'rinish">
          <TweakRadio
            label="Tema"
            value={tweaks.theme}
            onChange={v => setTweak("theme", v)}
            options={[
              { value: "light", label: "Kun" },
              { value: "dark",  label: "Tun" },
            ]}
          />
          <TweakRadio
            label="Asosiy rang"
            value={tweaks.accent}
            onChange={v => setTweak("accent", v)}
            options={[
              { value: "turquoise",   label: "Feruz" },
              { value: "saffron",     label: "Za'faron" },
              { value: "pomegranate", label: "Anor" },
            ]}
          />
        </TweakSection>

        <TweakSection label="Ekran">
          <TweakSelect
            label="Hozirgi ekran"
            value={screen}
            onChange={v => goto(v)}
            options={[
              { value: "splash",   label: "1. Splash" },
              { value: "onboard",  label: "2. Onboarding (3 qadam)" },
              { value: "dash",     label: "3. Asosiy" },
              { value: "apk",      label: "4. APK ro'yhati" },
              { value: "alert",    label: "5. Avto-skaner (xavfli)" },
              { value: "result",   label: "6. Tahlil natijasi" },
              { value: "settings", label: "7. Sozlamalar" },
              { value: "stats",    label: "8. Statistika" },
            ]}
          />
          <TweakSelect
            label="Namuna (tahlil uchun)"
            value={param || "rasmlar18"}
            onChange={v => setParam(v)}
            options={APK_SAMPLES.map(a => ({ value: a.id, label: a.name }))}
          />
        </TweakSection>

        <TweakSection label="Demo">
          <TweakSlider
            label="Himoya darajasi"
            value={tweaks.protection}
            min={0} max={100} step={1}
            onChange={v => setTweak("protection", v)}
          />
          <TweakToggle
            label="Avto-skaner ko'rsatish"
            value={tweaks.showAutoScan}
            onChange={v => setTweak("showAutoScan", v)}
          />
          <TweakButton label="Boshidan boshlash" onClick={() => goto("splash")} />
        </TweakSection>
      </TweaksPanel>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById("root")).render(<App/>);
