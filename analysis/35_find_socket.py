"""Stage 35: Find Socket/connect/HTTP methods in banker DEX with FULL bytecode + inline-decrypted literals."""
import os, sys, io, re, json, base64, logging
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from loguru import logger
logger.remove()
logging.disable(logging.CRITICAL)
from androguard.misc import AnalyzeAPK

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
UNPACK = os.path.join(OUT, "unpacked")

KEYS = {
    "RASMLAR_18_fixed.apk":          {"base": b"JYTAs0m31lxvwkQE42Y10Ktm",        "top": b"3183701586F97GhYNSURErMMwPeAS33H"},
    "RASMLAR_8_fixed.apk":           {"base": b"UmmYqVRrK46tVxAG5PDVUI5rSF72a6p1", "top": b"551712693drkGqsgO05tb31vbvZGkA0P"},
    "toydanfotolar9jpg_10_fixed.apk":{"base": b"AVbmxP9CNlQZRrzvnJhFw92n",         "top": b"347692886QFqc74H0w07DWz51sMHjqB4"},
}
for f in os.listdir(UNPACK):
    if f.startswith("VID_23856") and f.endswith("_fixed.apk"):
        KEYS[f] = {"base": b"C49yA8hXYvs5cz7wGeEX7cQLTzYn", "top": b"358200732kyGoz80aYtUropyXbvrXv9h"}

def dec(s, key):
    try:
        pad = (-len(s)) % 4
        raw = base64.b64decode(s + "="*pad, validate=False)
    except: return None
    pt = bytes(b ^ key[i%len(key)] for i, b in enumerate(raw))
    try:
        d = pt.decode("utf-8")
        printable = sum(1 for c in d if 32<=ord(c)<127 or c in "\n\r\t" or ord(c)>127) / max(1, len(d))
        if printable > 0.85: return d
    except: pass
    return None

def annotate(line, keys):
    m = re.search(r'const-string [vp]\d+, "([^"]*)"', line)
    if not m: return line
    s = m.group(1)
    if not re.match(r"^[A-Za-z0-9+/]{4,400}={0,2}$", s): return line
    for kn, k in keys.items():
        d = dec(s, k)
        if d:
            return f"{line}   ;; ({kn}->) {d!r}"
    return line

# Patterns we want — anything that does TCP/HTTP communication
NET_PATTERNS = [
    r"java/net/Socket;",
    r"java/net/InetSocketAddress",
    r"java/net/URL;",
    r"openConnection",
    r"HttpURLConnection",
    r"okhttp",
    r"Ljava/io/OutputStream;->write",
    r"Ljava/io/DataOutputStream",
    r"WebSocket",
    r"webkit/WebView;->loadUrl",
    r"newWebSocket",
    r"ktor",
    r"retrofit",
    r"connect\(",
    r"sendBroadcast",
    r"startService",
    r"bindService",
]

for apk_name in KEYS:
    apk = os.path.join(UNPACK, apk_name)
    if not os.path.isfile(apk): continue
    keys = KEYS[apk_name]
    print(f"\n{'#'*78}\n# {apk_name}\n{'#'*78}")
    a, d, dx = AnalyzeAPK(apk)

    for m in dx.get_methods():
        try:
            if m.is_external(): continue
            method = m.get_method()
            if not method or not method.get_code(): continue
            lines = []
            for ins in method.get_instructions():
                try:
                    lines.append(ins.get_name() + " " + ins.get_output())
                except: pass
            body = "\n".join(lines)
            # Only consider methods that actually touch network OR start service/broadcast with extra data
            hits = [p for p in NET_PATTERNS if re.search(p, body)]
            if not hits: continue
            # Only interested if it has Base64-encrypted string literals (or has params)
            lits = re.findall(r'const-string [vp]\d+, "([^"]*)"', body)
            b64_lits = [l for l in lits if l and re.match(r"^[A-Za-z0-9+/]{4,400}={0,2}$", l)]
            if not b64_lits: continue
            print(f"\n  ----- {m.full_name} -----")
            print(f"  hits: {hits}")
            print(f"  b64 literals: {len(b64_lits)}")
            for line in body.splitlines():
                print(f"    {annotate(line, keys)}")
        except: pass
