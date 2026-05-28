"""Stage 36: Full hunt in VIDEO.mp4 DEX — find HTTP / Socket / AES / WebView + decrypt strings inline."""
import os, sys, io, re, base64, logging
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from loguru import logger
logger.remove()
logging.disable(logging.CRITICAL)
from androguard.misc import AnalyzeAPK

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
UNPACK = os.path.join(OUT, "unpacked")

KEY = b"sqsmlH2NOLPXeaDIFGnMEOdG6Uc2mVin"

def dec(s):
    try:
        pad = (-len(s)) % 4
        raw = base64.b64decode(s + "="*pad, validate=False)
    except: return None
    pt = bytes(b ^ KEY[i%len(KEY)] for i, b in enumerate(raw))
    try:
        d = pt.decode("utf-8")
        printable = sum(1 for c in d if 32<=ord(c)<127 or c in "\n\r\t" or ord(c)>127) / max(1, len(d))
        if printable > 0.85: return d
    except: pass
    return None

def annotate(line):
    m = re.search(r'const-string [vp]\d+, "([^"]*)"', line)
    if not m: return line
    s = m.group(1)
    if not re.match(r"^[A-Za-z0-9+/]{4,400}={0,2}$", s): return line
    d = dec(s)
    if d:
        return f"{line}   ;; -> {d!r}"
    return line

apk = os.path.join(UNPACK, "VIDEO20012026mp4_2_fixed.apk")
a, d, dx = AnalyzeAPK(apk)

NET_PATTERNS = [
    "java/net/Socket;",
    "java/net/InetSocketAddress",
    "java/net/URL;",
    "openConnection",
    "HttpURLConnection",
    "okhttp",
    "java/io/DataOutputStream",
    "newWebSocket",
    "webkit/WebView;->loadUrl",
    "ktor",
    "retrofit",
    "PackageInstaller",
    "Cipher;->doFinal",
    "Cipher;->getInstance",
    "SecretKeySpec",
    "IvParameterSpec",
    "Runtime;->exec",
]

# First — get ALL methods that contain any network/crypto reference
hits_by_method = []
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
        patterns_found = [p for p in NET_PATTERNS if p in body]
        if patterns_found:
            hits_by_method.append((str(m.full_name), patterns_found, body))
    except: pass

print(f"[+] {len(hits_by_method)} methods touch network/crypto APIs")
# Sort by interestingness — crypto + net > net > crypto
def score(item):
    patterns = item[1]
    s = 0
    if any("Cipher" in p for p in patterns): s += 10
    if any("Socket" in p for p in patterns): s += 10
    if any("URL" in p for p in patterns): s += 10
    if any("okhttp" in p for p in patterns): s += 10
    if any("HttpURL" in p for p in patterns): s += 10
    if any("openConnection" in p for p in patterns): s += 10
    if "PackageInstaller" in str(patterns): s += 5
    if "loadUrl" in str(patterns): s += 5
    return -s

hits_by_method.sort(key=score)
# Print first 10 most interesting fully
shown = 0
for full_name, patterns, body in hits_by_method:
    lits = re.findall(r'const-string [vp]\d+, "([^"]*)"', body)
    b64_lits = [l for l in lits if l and re.match(r"^[A-Za-z0-9+/]{4,400}={0,2}$", l)]
    if not b64_lits and "Cipher" not in str(patterns) and "URL" not in str(patterns): continue
    print(f"\n{'='*78}")
    print(f"  METHOD: {full_name}")
    print(f"  HITS: {patterns}")
    print(f"  literals: {len(lits)}")
    print('='*78)
    for line in body.splitlines():
        print(f"    {annotate(line)}")
    shown += 1
    if shown >= 8: break
