/* ============================================================
   ANOR QALQON — Router · Bottom nav · Theme/Accent
   ============================================================ */

function App() {
  const [theme, setTheme]   = useState(() => localStorage.getItem("aq.theme") || "light");
  const [accent, setAccent] = useState(() => localStorage.getItem("aq.accent") || "anor");
  const [screen, setScreen] = useState(() => localStorage.getItem("aq.screen") || "dashboard");
  const [threat, setThreat] = useState(null);
  const [scanMode, setScanMode] = useState(null);
  const [dir, setDir] = useState("fwd");
  const prev = useRef(screen);

  const ORDER = ["language", "splash", "onboarding", "initialscan", "dashboard", "apps",
    "stats", "settings", "result", "permissions", "autoscan", "quarantine", "protection", "about", "report"];
  const nextDir = (to) => {
    const oi = ORDER.indexOf(prev.current), ni = ORDER.indexOf(to);
    setDir(ni >= oi ? "fwd" : "back"); prev.current = to;
  };

  useEffect(() => { document.documentElement.dataset.theme = theme; localStorage.setItem("aq.theme", theme); }, [theme]);
  useEffect(() => { document.documentElement.dataset.accent = accent; localStorage.setItem("aq.accent", accent); }, [accent]);
  useEffect(() => { localStorage.setItem("aq.screen", screen); }, [screen]);

  const go = (s) => { nextDir(s); setScreen(s); };
  const openThreat = (t) => { nextDir("result"); setThreat(t); setScreen("result"); };
  const runScan = () => { nextDir("autoscan"); setScanMode("manual"); setScreen("autoscan"); };

  const nav = { go, openThreat, scan: runScan, theme, setTheme, accent, setAccent, threat, scanMode, screen };
  window.__aq = { setTheme, setAccent, setScreen, go };

  useEffect(() => {
    const h = (e) => {
      if (!e.data || typeof e.data !== "object") return;
      if (e.data.type === "aq-theme") setTheme(e.data.value);
      if (e.data.type === "aq-accent") setAccent(e.data.value);
      if (e.data.type === "aq-screen") { nextDir(e.data.value); setThreat(window.THREATS[0]); setScreen(e.data.value); }
    };
    window.addEventListener("message", h); return () => window.removeEventListener("message", h);
  }, []);

  const noStatusBar = ["splash", "language", "onboarding", "initialscan", "autoscan"];
  const showNav = ["dashboard", "apps", "stats", "settings"];
  const hideStatus = noStatusBar.includes(screen);
  const hasNav = showNav.includes(screen);

  const Screens = {
    language: window.Language, splash: window.Splash, onboarding: window.Onboarding,
    initialscan: window.InitialScan, dashboard: window.Dashboard, apps: window.Apps,
    autoscan: window.AutoScan, result: window.ScanResult, stats: window.Stats,
    settings: window.Settings, quarantine: window.Quarantine, protection: window.Protection,
    about: window.About, report: window.Report, permissions: window.Permissions,
  };
  const Cur = Screens[screen] || (() => <div className="screen-pad">…</div>);

  return (
    <div className="stage">
      <div className="phone">
        {!hideStatus && (
          <div className="sbar">
            <span>9:41</span>
            <span className="punch" />
            <span className="sb-right">{I.wifi({ size: 16 })}{I.card({ size: 18 })}</span>
          </div>
        )}
        <div className={"screen pg-" + dir} key={screen}>
          <Cur {...nav} />
        </div>
        {hasNav && (
          <div className="bnav">
            {[["dashboard","Asosiy","shield"],["apps","Skaner","scan"],
              ["stats","Statistika","chart"],["settings","Sozlamalar","gear"]].map(([k, l, ic]) => (
              <button key={k} className={"nav" + (screen === k ? " on" : "")} onClick={() => go(k)}>
                <span className="pill">{I[ic]({ size: 22 })}</span>{l}
              </button>
            ))}
          </div>
        )}
        {screen !== "autoscan" && <div className="gnav" />}
      </div>
      {window.Tweaks && <window.Tweaks nav={nav} />}
    </div>
  );
}

ReactDOM.createRoot(document.getElementById("root")).render(<App />);
