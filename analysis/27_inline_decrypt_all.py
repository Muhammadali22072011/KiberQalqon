"""Stage 27: Decrypt EVERY single constant string in every banker DEX inline, then grep for URLs/tokens."""
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
        printable = sum(1 for c in d if 32<=ord(c)<127 or c in "\n\r\t") / max(1, len(d))
        if printable > 0.85: return d
    except: pass
    return None

# Get ALL decrypted plaintexts for each banker; then look for ones matching URL/method-call patterns
URL_PAT = re.compile(r"https?://[^\s\"'<>]{4,}")
DOM_PAT = re.compile(r"\b(?=[A-Za-z0-9-]{1,63}\.)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+(?:com|net|org|info|biz|ru|uz|xyz|top|club|site|online|store|shop|cc|me|tg|in|app|cyou|live|space|website|tk|ml|cf|ga|pw|bot|cn|io|sbs|fun|click|host|monster|surf|press|world|today|life|tech|cloud|dev|app|tools)\b", re.IGNORECASE)
TG_BOT_PAT = re.compile(r"\b\d{6,12}:[A-Za-z0-9_-]{30,60}\b")
PATH_PAT = re.compile(r"^/[A-Za-z0-9_\-/]{2,100}$")
IP_PAT = re.compile(r"\b(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\b")

# Generic "library" noise we don't care about
NOISE = re.compile(r"(google|jetbrains|apache|adobe|github|w3\.org|kotlin\.io|java\.io|openai|chatgpt|gstatic|cloudflare|cdn-cgi|developer\.android|schemas\.android|firebaseio|crashlyt|sentry|youtrack|kotlinx_coroutines|GitHub-Mark|androidx\.|getBytes|substring|charset|toString)", re.IGNORECASE)

for apk_name in KEYS:
    apk = os.path.join(UNPACK, apk_name)
    if not os.path.isfile(apk): continue
    keys = KEYS[apk_name]
    print(f"\n{'#'*78}\n# {apk_name}\n{'#'*78}")
    a, d, dx = AnalyzeAPK(apk)

    # Collect ALL strings from DEX
    all_strings = []
    for sv in dx.get_strings():
        try: all_strings.append(str(sv.get_value()))
        except: pass

    # Try every base64-shaped string against each key
    decrypted = set()
    for s in all_strings:
        if not re.match(r"^[A-Za-z0-9+/]{4,400}={0,2}$", s): continue
        for kn, k in keys.items():
            d_str = dec(s, k)
            if d_str and len(d_str) >= 3:
                decrypted.add(d_str)
                break

    print(f"  total decrypted plaintexts: {len(decrypted)}")
    pts = sorted(decrypted)

    # Find URLs (any HTTP)
    urls = set()
    doms = set()
    bots = set()
    paths = set()
    ips = set()
    for pt in pts:
        for m in URL_PAT.findall(pt): urls.add(m)
        for m in TG_BOT_PAT.findall(pt): bots.add(m)
        for m in IP_PAT.findall(pt):
            if not m.startswith(("127.","255.","0.","10.","192.168.","169.254.","2.5.","1.3.","1.10","1.101")):
                ips.add(m)
        # paths starting with /
        if pt.startswith("/") and len(pt) < 100:
            if PATH_PAT.match(pt) and not pt.startswith(("/dev/","/proc/","/sys/","/system/","/sdcard/","/data/","/storage/","/etc/","/cache/","/mnt/","/var/")):
                paths.add(pt)
        for m in DOM_PAT.findall(pt):
            if not NOISE.search(m): doms.add(m)

    print(f"\n  [URLs HTTP] {len(urls)}")
    for u in sorted(urls):
        if not NOISE.search(u): print(f"    {u}")

    print(f"\n  [Telegram bot tokens] {len(bots)}")
    for b in sorted(bots): print(f"    {b}")

    print(f"\n  [IPs] {len(ips)}")
    for ip in sorted(ips): print(f"    {ip}")

    print(f"\n  [Suspicious domains] {len(doms)}")
    for d in sorted(doms): print(f"    {d}")

    print(f"\n  [Path-like '/api/...'] {len(paths)}")
    for p in sorted(paths)[:50]: print(f"    {p}")

    # Bonus: SMS-related, contact-related, exfil-related plaintexts
    INTEREST_PAT = re.compile(r"(SMS|contact|getMessage|exfil|c2|server|host|admin|panel|sendMessage|sendDocument|sendPhoto|chat_id|botToken|api_key|secret|token|webhook|/api/|getBody|getAddress|getNumber|inbox|outbox|getDisplayMessageBody|getMessageBody|getOriginatingAddress)", re.IGNORECASE)
    print(f"\n  [SMS / exfil / API-style plaintexts]")
    seen = set()
    for pt in pts:
        if INTEREST_PAT.search(pt) and pt not in seen and not NOISE.search(pt):
            seen.add(pt)
            if len(seen) > 100: break
            print(f"    {pt[:240]}")

    # Save full decrypted list
    with open(os.path.join(OUT, f"27_pts_{apk_name.replace('.apk','').replace(' ','_')}.txt"), "w", encoding="utf-8") as f:
        for pt in pts:
            f.write(pt + "\n")
