"""Stage 10: Collect ALL Base64 strings (likely encrypted C2/config) from each banker DEX + native lib build paths."""
import os, sys, io, re, json, logging
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from loguru import logger
logger.remove()
logging.disable(logging.CRITICAL)
from androguard.misc import AnalyzeAPK

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
UNPACK = os.path.join(OUT, "unpacked")

apks = []
for f in os.listdir(UNPACK):
    if f.endswith("_fixed.apk"):
        apks.append(os.path.join(UNPACK, f))

results = {}
for p in apks:
    name = os.path.basename(p).replace("_fixed.apk","")
    print(f"\n{'='*72}\n[+] {name}\n{'='*72}")
    a, d, dx = AnalyzeAPK(p)
    strings = []
    for sv in dx.get_strings():
        try: strings.append(str(sv.get_value()))
        except: pass
    # Capture Base64 32-300 chars (likely encrypted strings)
    b64s = []
    pat = re.compile(r"^[A-Za-z0-9+/]{24,400}={0,2}$")
    for s in strings:
        if pat.match(s) and not s.isdigit():
            # skip pure hex-looking
            if re.fullmatch(r"[0-9a-fA-F]+", s): continue
            b64s.append(s)
    b64s = sorted(set(b64s), key=len, reverse=True)
    print(f"  Base64-like encrypted strings: {len(b64s)}")
    for s in b64s[:30]:
        print(f"    {s}")
    # also look for any hardcoded plain-text indicators
    interesting = []
    for s in strings:
        sl = s.lower()
        if any(k in sl for k in ["telegram", "api.telegram", "bot", "://", "uzbek", "tashkent", "tbc.uz", "alif", "anor", "ipak", "kapital", "uzcard", "humo", "click.uz", "payme",
                                  "accessibility", "dexclassloader", "loadclass", "performglobal", "dispatchgesture",
                                  "android.permission.bind_accessibility", "android.permission.system_alert", "/system/bin/su",
                                  "decrypt", "encrypt", "javax.crypto", "aes/cbc", "aes/gcm", "okhttp", "websocket"]):
            interesting.append(s[:300])
    interesting = list(dict.fromkeys(interesting))[:80]
    print(f"  --- interesting plain strings: {len(interesting)} ---")
    for s in interesting[:60]:
        print(f"    PLAIN: {s}")
    results[name] = {
        "base64_encrypted_strings": b64s[:200],
        "plain_text_indicators": interesting,
    }

with open(os.path.join(OUT, "10_ioc_strings.json"), "w", encoding="utf-8") as f:
    json.dump(results, f, ensure_ascii=False, indent=2)
print(f"\n[+] Saved -> {OUT}\\10_ioc_strings.json")
