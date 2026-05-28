/* eslint-disable */
/* ============================================================
   KiberQalqon — Auto-scan alert + Scan result detail
   ============================================================ */

const AutoScanAlert = ({ goto, sampleId = "rasmlar18" }) => {
  const [phase, setPhase] = React.useState("scanning"); // scanning -> result
  const [progress, setProgress] = React.useState(0);
  const sample = APK_SAMPLES.find(a => a.id === sampleId) || APK_SAMPLES[0];

  React.useEffect(() => {
    if (phase !== "scanning") return;
    let i = 0;
    const t = setInterval(() => {
      i += 7 + Math.random() * 6;
      if (i >= 100) { i = 100; clearInterval(t); setTimeout(() => setPhase("result"), 350); }
      setProgress(Math.min(100, i));
    }, 110);
    return () => clearInterval(t);
  }, [phase]);

  if (phase === "scanning") {
    return (
      <div className="fade-in" style={{
        position: "absolute", inset: 0,
        background: "linear-gradient(180deg, #06141a 0%, #02080b 100%)",
        color: "#e6f7fa",
        display: "flex", flexDirection: "column",
        overflow: "hidden",
      }}>
        {/* pattern */}
        <div style={{
          position: "absolute", inset: 0,
          backgroundImage: `url("data:image/svg+xml;utf8,${encodeURIComponent(
            `<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 80 80' width='80' height='80'>
              <g fill='none' stroke='%2300BCD4' stroke-width='1' opacity='0.18'>
                <path d='M40 8 L48 32 L72 40 L48 48 L40 72 L32 48 L8 40 L32 32 Z'/>
              </g></svg>`
          )}")`,
          backgroundSize: "80px 80px",
          opacity: 0.5,
        }} />
        <div className="scan-line" style={{ top: 0 }}/>

        {/* status bar reuse */}
        <div style={{ height: 36 }}/>

        {/* hero */}
        <div style={{
          flex: 1, display: "flex", flexDirection: "column",
          alignItems: "center", justifyContent: "center",
          padding: "0 28px",
          position: "relative",
        }}>
          <div style={{
            font: "600 11px/1 var(--font-mono)",
            letterSpacing: "0.24em",
            color: "#5eead4",
            textTransform: "uppercase",
            marginBottom: 12,
          }}>// AVTOMATIK SKANER</div>

          <div style={{ position: "relative", width: 220, height: 220, display: "grid", placeItems: "center" }}>
            <div className="pulse-ring" style={{ background: "rgba(20, 184, 200, 0.22)" }}/>
            <div className="pulse-ring" style={{ background: "rgba(20, 184, 200, 0.22)", animationDelay: "0.8s" }}/>
            <svg width="220" height="220" viewBox="0 0 220 220" style={{ position: "absolute", inset: 0 }}>
              <circle cx="110" cy="110" r="92" fill="none" stroke="rgba(20,184,200,0.25)" strokeWidth="2"/>
              <circle cx="110" cy="110" r="92" fill="none" stroke="#5eead4" strokeWidth="3"
                strokeLinecap="round"
                strokeDasharray={2*Math.PI*92}
                strokeDashoffset={2*Math.PI*92 * (1 - progress/100)}
                transform="rotate(-90 110 110)"
                style={{ transition: "stroke-dashoffset .12s linear" }}/>
            </svg>
            <div style={{
              width: 130, height: 130, borderRadius: "50%",
              background: "rgba(20,184,200,0.12)",
              border: "1px solid rgba(94,234,212,0.35)",
              display: "grid", placeItems: "center",
              backdropFilter: "blur(4px)",
            }}>
              <div style={{
                font: "700 38px/1 var(--font-display)", color: "#fff",
                letterSpacing: "-0.02em",
              }}>{Math.floor(progress)}<span style={{ fontSize: 18, color: "#5eead4" }}>%</span></div>
            </div>
          </div>

          <div style={{
            marginTop: 28, font: "700 22px/1.18 var(--font-display)",
            color: "#fff", textAlign: "center", letterSpacing: "-0.01em",
          }}>Yangi APK tekshirilmoqda</div>
          <div style={{
            marginTop: 6, font: "500 13px/1.4 var(--font-mono)", color: "#a8d5d9", textAlign: "center",
            wordBreak: "break-all", maxWidth: 260,
          }}>{sample.name}</div>

          {/* terminal */}
          <div className="term" style={{ marginTop: 24, width: "100%", maxWidth: 320 }}>
            <div><span className="c"># {sample.source} → Downloads/</span></div>
            <div><span className="k">[+]</span> FileObserver: CLOSE_WRITE</div>
            <div><span className="k">[+]</span> ZIP-evasion check… <span className="s">GP-flag=0x01</span></div>
            <div><span className="k">[+]</span> Manifest unpack… <span className="s">OK</span></div>
            <div><span className="k">[+]</span> DEX scan…  <span className="s">ajina pattern</span></div>
            <div><span className="k">[!]</span> SMS_READ + ACCESSIBILITY <span className="c">/* danger */</span></div>
          </div>
        </div>
      </div>
    );
  }

  /* Result phase — danger */
  return (
    <div className="alert-frame fade-in">
      <div style={{ height: 36 }}/>
      {/* big icon */}
      <div style={{
        flex: 1, padding: "10px 28px 16px",
        display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "flex-start",
        overflow: "auto",
      }}>
        <div style={{
          marginTop: 20,
          width: 96, height: 96, borderRadius: "50%",
          background: "var(--danger)",
          display: "grid", placeItems: "center",
          boxShadow: "0 0 0 10px rgba(224, 57, 75, 0.18), 0 0 0 22px rgba(224, 57, 75, 0.08)",
          color: "#fff",
        }}>
          <I.alert size={48}/>
        </div>

        <div style={{
          marginTop: 24,
          font: "800 30px/1.05 var(--font-display)",
          letterSpacing: "-0.02em",
          textAlign: "center",
        }}>XAVFLI APK!</div>

        <div style={{
          marginTop: 8,
          font: "500 14px/1.4 var(--font-body)",
          color: "rgba(255,255,255,0.78)",
          textAlign: "center",
          maxWidth: 280,
        }}>Yuklab olingan fayl <b>{sample.family}</b> bank troyani oilasiga tegishli.</div>

        <div style={{
          marginTop: 24, width: "100%", maxWidth: 320,
          background: "rgba(255,255,255,0.06)",
          border: "1px solid rgba(255,255,255,0.10)",
          borderRadius: 18, padding: "14px 14px",
        }}>
          <div className="between" style={{ alignItems: "flex-start" }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{
                font: "500 10px/1 var(--font-mono)", color: "#ff9aa6",
                letterSpacing: "0.16em", textTransform: "uppercase",
              }}>Fayl</div>
              <div style={{
                font: "700 14px/1.25 var(--font-display)", color: "#fff",
                marginTop: 4, wordBreak: "break-all",
              }}>{sample.name}</div>
            </div>
            <span className="sev crit"><span className="dot"/>{sample.label}</span>
          </div>
          <div style={{ height: 1, background: "rgba(255,255,255,0.08)", margin: "12px 0" }}/>
          <div className="mono" style={{
            font: "500 11px/1.55 var(--font-mono)",
            color: "rgba(255,255,255,0.7)",
          }}>
            <div>paket: <span style={{ color: "#fff" }}>{sample.pkg}</span></div>
            <div>sha1:  <span style={{ color: "#fff" }}>{sample.sha1}</span></div>
            <div>oila:  <span style={{ color: "#ff9aa6" }}>{sample.family}</span></div>
            <div>manba: <span style={{ color: "#fff" }}>{sample.source}</span></div>
          </div>
        </div>

        <div style={{
          marginTop: 14, width: "100%", maxWidth: 320,
        }}>
          <div style={{
            font: "600 10px/1 var(--font-mono)", color: "rgba(255,255,255,0.55)",
            letterSpacing: "0.16em", textTransform: "uppercase", marginBottom: 8,
          }}>Xavfli ruxsatlar</div>
          <div style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
            {sample.perms.map(p => (
              <span key={p} style={{
                padding: "4px 10px", borderRadius: 999,
                background: "rgba(224,57,75,0.18)",
                color: "#ffd4d9",
                font: "600 10.5px/1.4 var(--font-mono)",
                border: "1px solid rgba(224,57,75,0.35)",
              }}>{p}</span>
            ))}
          </div>
        </div>

        <div style={{
          marginTop: 18, width: "100%", maxWidth: 320,
          font: "500 11px/1.5 var(--font-mono)",
          color: "rgba(255,255,255,0.5)",
          textAlign: "center",
        }}>
          5 soniyadan keyin avtomatik o'chiriladi…
        </div>
      </div>

      <div style={{ padding: "12px 24px 26px", display: "flex", gap: 10 }}>
        <button className="btn danger" style={{ flex: 2 }} onClick={() => goto("dash")}>
          <I.trash size={16}/> Hozir o'chirish
        </button>
        <button className="btn ghost" style={{ flex: 1, color: "#fff", borderColor: "rgba(255,255,255,0.25)" }}
          onClick={() => goto("result", sample.id)}>
          Batafsil
        </button>
      </div>
    </div>
  );
};

/* ── Scan Result detail ───────────────────────────────────── */
const ScanResult = ({ goto, sampleId }) => {
  const sample = APK_SAMPLES.find(a => a.id === sampleId) || APK_SAMPLES[0];
  const isDanger = sample.color === "danger";
  const isVideo = sample.id === "video";

  return (
    <div className="fade-in" style={{ position: "relative" }}>
      {/* Banner head */}
      <div style={{
        background: isDanger
          ? "linear-gradient(160deg, #c12d3e 0%, #7a1722 100%)"
          : "linear-gradient(160deg, var(--primary) 0%, var(--primary-2) 100%)",
        color: "#fff",
        padding: "10px 18px 22px",
        position: "relative",
      }}>
        <div style={{
          position: "absolute", inset: 0,
          backgroundImage: `url("data:image/svg+xml;utf8,${encodeURIComponent(
            `<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 80 80' width='80' height='80'>
              <g fill='none' stroke='white' stroke-width='1' opacity='0.16'>
                <path d='M40 8 L48 32 L72 40 L48 48 L40 72 L32 48 L8 40 L32 32 Z'/>
              </g></svg>`
          )}")`,
          backgroundSize: "80px 80px",
        }} />
        <div className="between" style={{ position: "relative" }}>
          <button className="icon-btn" onClick={() => goto("apk")}
            style={{ background: "rgba(255,255,255,0.12)", borderColor: "rgba(255,255,255,0.18)", color: "#fff" }}>
            <I.chevL size={18}/>
          </button>
          <div style={{ font: "600 13px/1 var(--font-display)", letterSpacing: "0.02em" }}>Tahlil natijasi</div>
          <button className="icon-btn"
            style={{ background: "rgba(255,255,255,0.12)", borderColor: "rgba(255,255,255,0.18)", color: "#fff" }}>
            <I.upload size={18}/>
          </button>
        </div>

        <div style={{ position: "relative", marginTop: 18, display: "flex", alignItems: "flex-start", gap: 14 }}>
          <div style={{
            width: 56, height: 56, borderRadius: 18,
            background: "rgba(255,255,255,0.15)",
            border: "1px solid rgba(255,255,255,0.25)",
            display: "grid", placeItems: "center", flexShrink: 0,
          }}>
            {isDanger ? <I.bug size={28}/> : <I.check size={28}/>}
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="mono" style={{
              font: "500 10px/1 var(--font-mono)",
              letterSpacing: "0.16em", opacity: 0.8, textTransform: "uppercase",
            }}>{sample.source}</div>
            <div style={{
              font: "800 22px/1.18 var(--font-display)",
              letterSpacing: "-0.015em", marginTop: 4, wordBreak: "break-all",
            }}>{sample.name}</div>
            <div style={{ marginTop: 10 }}>
              <span className={`sev ${sample.sev}`} style={{
                background: "rgba(255,255,255,0.18)", color: "#fff",
              }}><span className="dot"/>{sample.label}</span>
              {sample.family && (
                <span style={{
                  marginLeft: 8,
                  font: "700 11px/1 var(--font-mono)",
                  letterSpacing: "0.04em",
                  padding: "5px 10px", borderRadius: 6,
                  background: "rgba(0,0,0,0.18)", color: "#fff",
                }}>{sample.family}</span>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Body */}
      <div className="screen-pad" style={{ paddingTop: 18 }}>

        {/* Summary card */}
        <div className="card" style={{ padding: "14px 14px" }}>
          <div className="h-eyebrow">QISQACHA</div>
          <div style={{ font: "500 14px/1.5 var(--font-body)", color: "var(--ink)", marginTop: 6 }}>
            {isDanger
              ? <>Bu fayl <b>{sample.family}</b> oilasiga tegishli {isVideo ? "dropper. Tashqi serverlarga ulanadi va keyingi bosqichdagi banker o'rnatishga harakat qiladi" : "bank troyani. SMS-OTP kodlarini o'g'irlash uchun ishlatiladi"}.</>
              : <>Fayl rasmiy do'kondan, imzosi tekshirildi, xavfli xulq-atvor aniqlanmadi.</>}
          </div>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10, marginTop: 14 }}>
            {[
              { l: "Hajm", v: sample.size },
              { l: "Manba", v: sample.source },
              { l: "Aniqlangan", v: sample.when },
              { l: "Status", v: isDanger ? "Karantinda" : "Ruxsat etilgan" },
            ].map(x => (
              <div key={x.l}>
                <div style={{
                  font: "600 10px/1 var(--font-mono)", color: "var(--ink-3)",
                  letterSpacing: "0.12em", textTransform: "uppercase",
                }}>{x.l}</div>
                <div style={{
                  font: "600 13px/1.2 var(--font-display)",
                  color: "var(--ink)", marginTop: 4,
                }}>{x.v}</div>
              </div>
            ))}
          </div>
        </div>

        {isDanger && (
          <>
            {/* Anti-analysis techniques */}
            <div className="head-row" style={{ marginTop: 22, marginBottom: 10 }}>
              <div>
                <div className="h-eyebrow">ANTI-TAHLIL HIYLALARI</div>
                <div style={{ font: "700 18px/1.2 var(--font-display)", color: "var(--ink)", marginTop: 4 }}>
                  Yashirin texnikalar
                </div>
              </div>
            </div>
            <div className="card">
              {[
                { i: <I.lock/>, n: "ZIP-evasion",  d: "Har bir fayl GP-flag=0x01 (encrypted) ko'rsatilgan. Antiviruslar ocha olmaydi, lekin Android o'rnatadi.", on: true },
                { i: <I.bug/>,  n: "Anti-Frida",   d: "/data/local/tmp/frida-server va /proc/self/maps frida/gadget/substrate qidiriladi.", on: true },
                { i: <I.shield/>, n: "Anti-Magisk", d: "com.topjohnwu.magisk paketi tekshiriladi — ildiz topilsa ishga tushmaydi.", on: true },
                { i: <I.globe/>,  n: "Anti-VPN",   d: "android.net.VpnService faolligi tekshiriladi. HTML overlay'da 'Disable VPN' so'raydi.", on: true },
              ].map((t, i, arr) => (
                <div key={t.n} className="li-row" style={{
                  borderBottom: i < arr.length - 1 ? "1px solid var(--hairline)" : "none",
                  cursor: "default",
                }}>
                  <div className="ico" style={{ background: "var(--warn-bg)", color: "var(--warn-ink)" }}>
                    {t.i}
                  </div>
                  <div className="meta">
                    <div className="h">{t.n}</div>
                    <div style={{
                      font: "400 11.5px/1.4 var(--font-body)",
                      color: "var(--ink-2)", marginTop: 4,
                      whiteSpace: "normal",
                    }}>{t.d}</div>
                  </div>
                </div>
              ))}
            </div>

            {/* Crypto keys / IOC */}
            <div className="head-row" style={{ marginTop: 22, marginBottom: 10 }}>
              <div>
                <div className="h-eyebrow">XOR/AES KALITLARI</div>
                <div style={{ font: "700 18px/1.2 var(--font-display)", color: "var(--ink)", marginTop: 4 }}>
                  Statik tahlilda ochildi
                </div>
              </div>
            </div>
            <div className="term">
              {isVideo ? (
                <>
                  <div><span className="c">/* RoundRift dropper — native libdan */</span></div>
                  <div><span className="k">key</span> = <span className="s">"sqsmlH2NOLPXeaDIFGnMEOdG6Uc2mVin"</span></div>
                  <div><span className="k">algo</span> = <span className="s">"XOR + Base64"</span></div>
                  <div><span className="k">symbol</span> = <span className="s">"_ZL6encKey"</span></div>
                  <div><span className="k">class</span> = <span className="s">"ydbllnjd.com.core.KeyManager"</span></div>
                  <div><span className="k">strings</span> = <span className="s">41</span> <span className="c">// 15 base64, 26 xor</span></div>
                </>
              ) : (
                <>
                  <div><span className="c">/* Ajina.Banker — DEX'dan ajratildi */</span></div>
                  <div><span className="k">base_key</span> = <span className="s">"JYTAs0m31lxvwkQE42Y10Ktm"</span></div>
                  <div><span className="k">top_key</span>  = <span className="s">"3183701586F97GhYNSURErMM…"</span></div>
                  <div><span className="k">algo</span>     = <span className="s">"2x XOR + Base64"</span></div>
                  <div><span className="k">strings</span>  = <span className="s">1310</span> <span className="c">// ochildi</span></div>
                </>
              )}
            </div>

            {/* C2 / IOC */}
            <div className="head-row" style={{ marginTop: 22, marginBottom: 10 }}>
              <div>
                <div className="h-eyebrow">TARMOQ IOC</div>
                <div style={{ font: "700 18px/1.2 var(--font-display)", color: "var(--ink)", marginTop: 4 }}>
                  Aniqlangan C2 manzillar
                </div>
              </div>
            </div>
            <div className="card">
              {[
                { d: "elrxzx.com", t: "C2 (asosiy)", b: true },
                { d: "ilovekkksfm.com", t: "Dropper", b: true },
                { d: "ilovekkksfm.com/video/dropper.html", t: "Entry URL", b: true },
              ].map((c, i, arr) => (
                <div key={c.d} className="li-row" style={{
                  borderBottom: i < arr.length - 1 ? "1px solid var(--hairline)" : "none",
                  cursor: "default",
                }}>
                  <div className="ico" style={{
                    background: "var(--danger-bg)", color: "var(--danger-ink)",
                  }}>
                    <I.globe size={20}/>
                  </div>
                  <div className="meta">
                    <div className="h mono" style={{
                      whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis"
                    }}>{c.d}</div>
                    <div className="s">{c.t} · qora ro'yhatda</div>
                  </div>
                  <span className="chip danger" style={{ height: 22 }}>BLOK</span>
                </div>
              ))}
            </div>

            {/* HTML overlay preview */}
            <div className="head-row" style={{ marginTop: 22, marginBottom: 10 }}>
              <div>
                <div className="h-eyebrow">HTML OVERLAY</div>
                <div style={{ font: "700 18px/1.2 var(--font-display)", color: "var(--ink)", marginTop: 4 }}>
                  Soxta yangilanish oynasi
                </div>
              </div>
            </div>
            <div className="card" style={{ padding: 14 }}>
              <div style={{
                borderRadius: 12, overflow: "hidden",
                background: "linear-gradient(180deg, #f4f4f6 0%, #e9e9ee 100%)",
                border: "1px solid var(--hairline)",
                color: "#1a1a1a",
                padding: "14px 14px 16px",
              }}>
                <div style={{ font: "600 10px/1 var(--font-mono)", color: "#5a6068", letterSpacing: "0.12em" }}>← FAYK OVERLAY (*.spe)</div>
                <div style={{ font: "700 18px/1.18 var(--font-display)", marginTop: 8 }}>Yangilanish mavjud</div>
                <div style={{ font: "400 12px/1.45 var(--font-body)", color: "#3a3f47", marginTop: 4 }}>
                  Ilovadan foydalanish uchun yangilanishni o'rnatishingiz kerak. Hajmi: 1.6 MB.
                </div>
                <div style={{ display: "flex", gap: 8, marginTop: 14 }}>
                  <div style={{
                    flex: 1, height: 36, borderRadius: 999,
                    background: "#1a73e8", color: "#fff",
                    display: "grid", placeItems: "center",
                    font: "600 12px/1 var(--font-display)",
                  }}>O'rnatish</div>
                  <div style={{
                    width: 90, height: 36, borderRadius: 999,
                    background: "transparent", border: "1px solid #c1c5cc",
                    color: "#3a3f47",
                    display: "grid", placeItems: "center",
                    font: "600 12px/1 var(--font-display)",
                  }}>Batafsil</div>
                </div>
              </div>
              <div style={{
                marginTop: 12, font: "500 11px/1.45 var(--font-body)", color: "var(--ink-2)",
              }}>
                3 tilda (O'zbek · Русский · Français) tayyorlangan. WebView ↔ Java ko'prigi orqali bosish bankerni ishga tushiradi.
              </div>
            </div>
          </>
        )}

        {/* Actions */}
        <div style={{ marginTop: 22, display: "flex", flexDirection: "column", gap: 8 }}>
          {isDanger ? (
            <>
              <button className="btn danger block" onClick={() => goto("apk")}>
                <I.trash size={16}/> Telefondan o'chirish
              </button>
              <button className="btn ghost block">
                <I.upload size={16}/> Serverga yuborish (tahlil)
              </button>
            </>
          ) : (
            <button className="btn primary block" onClick={() => goto("apk")}>
              <I.check size={16}/> Tushunarli
            </button>
          )}
        </div>

      </div>
    </div>
  );
};

Object.assign(window, { AutoScanAlert, ScanResult });
