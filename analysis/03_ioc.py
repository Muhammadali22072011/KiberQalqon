"""Stage 3: Extract URLs, IPs, Telegram tokens, suspicious strings, asset files, native libs."""
import os, sys, json, io, logging, re, zipfile, hashlib
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from loguru import logger
logger.remove()
logging.disable(logging.CRITICAL)
from androguard.misc import AnalyzeAPK

SAMPLES_DIR = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\новые вирусы"
OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"

URL_RE       = re.compile(r"https?://[A-Za-z0-9._\-/%?=&:#~+]{4,200}")
IPV4_RE      = re.compile(r"\b(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\b")
DOMAIN_RE    = re.compile(r"\b(?=[A-Za-z0-9-]{1,63}\.)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+(?:com|net|org|info|biz|ru|uz|xyz|top|club|site|online|store|shop|cc|me|tg|in|app|cyou|live|space|website|tk|ml|cf|ga)\b")
TG_BOT_RE    = re.compile(r"\b\d{6,12}:[A-Za-z0-9_-]{30,50}\b")
TG_CHAT_RE   = re.compile(r"-?100\d{8,12}")
B64LONG_RE   = re.compile(r"\b[A-Za-z0-9+/]{32,}={0,2}\b")
HEX_RE       = re.compile(r"\b[0-9a-fA-F]{32,}\b")

KEYWORDS = ["bot", "token", "chat_id", "secret", "key", "password", "api/", "/cmd",
            "/upload", "/dump", "/sms", "/contact", "/log", "telegram", "ngrok",
            "amazonaws", "cloudfront", "ddns", "duckdns", "no-ip",
            "lyrics", "wedding", "toy", "rasm", "kuyov", "kelin",
            "bank", "uzcard", "humo", "tbc", "tenge", "click", "payme", "uztelekom",
            "sms", "ussd", "sber", "alif", "kapital", "ipak", "anor", "infinbank",
            "uzum", "lybarbank", "asakabank", "qishloq", "agrobank", "xalq",
            "AccessibilityService", "MediaProjection", "REQUEST_INSTALL"]

ALLOWLIST = re.compile(r"(?:schemas\.android\.com|googleusercontent|googletagmanager|google-analytics|firebaseio|gstatic|googleapis|googleusercontent|akamai|fontawesome|w3\.org|github\.io|apache\.org|json-schema)")

def extract_strings_from_apk(p):
    """Get strings via androguard + raw bytes from each ZIP member as a fallback."""
    a, d, dx = AnalyzeAPK(p)
    strings = set()
    for sv in dx.get_strings():
        try:
            strings.add(str(sv.get_value()))
        except Exception:
            pass
    # also scan raw zip members (assets, lib, resources)
    raw_members = {}
    with zipfile.ZipFile(p) as z:
        for info in z.infolist():
            name = info.filename
            try:
                data = z.read(name)
            except Exception:
                continue
            raw_members[name] = {
                "size": info.file_size,
                "sha256": hashlib.sha256(data).hexdigest()[:32],
            }
            # only scan small/text-like for strings
            if info.file_size < 5_000_000:
                # printable ascii runs >= 6
                for m in re.findall(rb"[\x20-\x7e]{6,}", data):
                    try:
                        strings.add(m.decode("ascii", "ignore"))
                    except Exception:
                        pass
    return strings, raw_members, a

samples = sorted([f for f in os.listdir(SAMPLES_DIR) if f.lower().endswith(".apk")])
results = {}

for s in samples:
    p = os.path.join(SAMPLES_DIR, s)
    print(f"\n{'='*72}\n[+] {s}\n{'='*72}")
    strings, members, a = extract_strings_from_apk(p)

    urls    = {u for u in {m for st in strings for m in URL_RE.findall(st)} if not ALLOWLIST.search(u)}
    ips     = {ip for ip in {m for st in strings for m in IPV4_RE.findall(st)} if not ip.startswith(("127.","0.","255.","10.0.0","192.168.","169.254."))}
    tg_bots = {m for st in strings for m in TG_BOT_RE.findall(st)}
    tg_chats= {m for st in strings for m in TG_CHAT_RE.findall(st)}
    domains = {d for d in {m for st in strings for m in DOMAIN_RE.findall(st)} if not ALLOWLIST.search(d) and not d.endswith((".so", ".dex", ".apk", ".xml", ".kt", ".java", ".png", ".jpg", ".webp"))}

    # long-base64 candidates — likely encrypted config
    b64s = set()
    for st in strings:
        for m in B64LONG_RE.findall(st):
            # skip cert-style padding-free hex etc; keep mostly mixed-case
            if not re.fullmatch(r"[0-9a-fA-F]+", m) and 40 <= len(m) <= 400:
                b64s.add(m)

    # keyword hits
    kw_hits = []
    kw_lower = [k.lower() for k in KEYWORDS]
    for st in strings:
        sl = st.lower()
        for k in kw_lower:
            if k in sl:
                kw_hits.append(st[:200])
                break
    kw_hits = list(dict.fromkeys(kw_hits))[:100]

    # assets / lib / dex inventory
    assets = {n: v for n, v in members.items() if n.startswith("assets/")}
    libs   = {n: v for n, v in members.items() if n.startswith("lib/")}
    dexes  = {n: v for n, v in members.items() if n.endswith(".dex")}

    print(f"  strings={len(strings)}  members={len(members)}")
    print(f"  urls={len(urls)} ips={len(ips)} tg_bots={len(tg_bots)} tg_chats={len(tg_chats)} domains(seed)={len(domains)} b64candidates={len(b64s)}")
    print(f"  assets={len(assets)}  lib/={len(libs)}  dex={len(dexes)}")
    for u in list(urls)[:20]:        print(f"    URL    : {u}")
    for ip in list(ips)[:20]:        print(f"    IP     : {ip}")
    for t in list(tg_bots)[:5]:      print(f"    TG_BOT : {t}")
    for c in list(tg_chats)[:5]:     print(f"    TG_CHAT: {c}")
    for d in list(domains)[:20]:     print(f"    DOM    : {d}")
    for n in list(assets)[:30]:
        print(f"    asset  : {n}  size={assets[n]['size']}")
    for n in list(libs)[:30]:
        print(f"    lib    : {n}  size={libs[n]['size']}")

    results[s] = {
        "strings_count": len(strings),
        "urls": sorted(urls),
        "ips": sorted(ips),
        "telegram_bots": sorted(tg_bots),
        "telegram_chats": sorted(tg_chats),
        "domains_seed": sorted(domains),
        "base64_candidates": sorted(b64s)[:50],
        "keyword_hits": kw_hits,
        "assets": assets,
        "libs": libs,
        "dexes": dexes,
        "all_members_count": len(members),
    }

with open(os.path.join(OUT, "03_ioc.json"), "w", encoding="utf-8") as f:
    json.dump(results, f, ensure_ascii=False, indent=2)
print(f"\n[+] Saved -> {OUT}\\03_ioc.json")
