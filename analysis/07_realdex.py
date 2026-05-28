"""Stage 7: Analyze the REAL classes.dex + assets in each unpacked APK."""
import os, sys, io, json, logging, re, math, struct
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from loguru import logger
logger.remove()
logging.disable(logging.CRITICAL)

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
UNPACK = os.path.join(OUT, "unpacked")

URL_RE     = re.compile(rb"https?://[A-Za-z0-9._\-/%?=&:#~+]{4,200}")
DOMAIN_RE  = re.compile(rb"\b(?=[A-Za-z0-9-]{1,63}\.)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+(?:com|net|org|info|biz|ru|uz|xyz|top|club|site|online|store|shop|cc|me|tg|in|app|cyou|live|space|website|tk|ml|cf|ga|pw|bot)\b")
IP_RE      = re.compile(rb"\b(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\b")
TG_BOT_RE  = re.compile(rb"\b\d{6,12}:[A-Za-z0-9_-]{30,50}\b")
TG_CHAT_RE = re.compile(rb"chat[_\-]?id[\"\':= ]+(-?100\d{8,12})|/sendMessage\?chat_id=(-?\d{6,})")
BIG_B64    = re.compile(rb"\b[A-Za-z0-9+/]{60,}={0,2}\b")
KEYWORDS = [
    b"bot", b"token", b"chat_id", b"telegram", b"api.telegram.org",
    b"sendMessage", b"sendDocument", b"sendPhoto",
    b"AccessibilityService", b"MediaProjection", b"DexClassLoader",
    b"sms", b"SMS", b"RECEIVE_SMS", b"BIND_ACCESSIBILITY", b"contacts",
    b"banking", b"bank", b"login", b"password", b"card", b"uzcard", b"humo",
    b"click.uz", b"payme", b"asaka", b"ipak", b"kapital", b"qishloq", b"agrobank",
    b"infinbank", b"xalq", b"anor", b"tbc", b"alif",
    b"setComponentEnabledSetting", b"DevicePolicy", b"DeviceAdmin",
    b"installPackage", b"PackageInstaller", b"REQUEST_INSTALL",
    b"ndk", b"native", b"System.loadLibrary",
    b"AES/CBC", b"AES/GCM", b"javax.crypto", b"Cipher",
    b"OkHttp", b"retrofit", b"Socket", b"WebSocket", b"WebView",
    b"performGlobalAction", b"dispatchGesture", b"GLOBAL_ACTION",
    b"overlay", b"TYPE_APPLICATION_OVERLAY",
    b"HostApduService", b"NFC", b"IsoDep", b"transceive",
]

DEX_MAGIC = b"dex\n0"

def entropy(b):
    if not b: return 0
    from collections import Counter
    n=len(b); c=Counter(b)
    return -sum((v/n)*math.log2(v/n) for v in c.values())

def scan_file(p):
    with open(p, "rb") as f:
        data = f.read()
    text_runs = re.findall(rb"[\x20-\x7e]{6,}", data)
    big_text = b"\n".join(text_runs)
    urls   = sorted({m.decode("ascii", "ignore") for m in URL_RE.findall(big_text)})
    doms   = sorted({m.decode("ascii", "ignore") for m in DOMAIN_RE.findall(big_text)})
    ips    = sorted({m.decode("ascii", "ignore") for m in IP_RE.findall(big_text)})
    bots   = sorted({m.decode("ascii", "ignore") for m in TG_BOT_RE.findall(big_text)})
    b64s   = sorted({m.decode("ascii", "ignore") for m in BIG_B64.findall(big_text)})[:50]
    kw = {}
    for k in KEYWORDS:
        n = big_text.count(k)
        if n: kw[k.decode()] = n
    return {
        "size": len(data),
        "entropy": round(entropy(data[:1_500_000]), 3),
        "head16_hex": data[:16].hex(),
        "is_dex": data.startswith(DEX_MAGIC),
        "is_elf": data.startswith(b"\x7fELF"),
        "urls": urls,
        "domains": doms,
        "ips": ips,
        "telegram_bots": bots,
        "keyword_counts": kw,
        "big_b64_samples": b64s,
        "embedded_dex_offsets": [m.start() for m in re.finditer(DEX_MAGIC, data)],
        "embedded_elf_offsets": [m.start() for m in re.finditer(b"\x7fELF", data)],
        "embedded_zip_offsets": [m.start() for m in re.finditer(b"PK\x03\x04", data)],
    }

ALL = {}
for d in sorted(os.listdir(UNPACK)):
    full = os.path.join(UNPACK, d)
    if not os.path.isdir(full): continue
    print(f"\n{'='*72}\n[+] {d}\n{'='*72}")
    sample = {}
    for root, _, fs in os.walk(full):
        for fn in fs:
            p = os.path.join(root, fn)
            rel = os.path.relpath(p, full).replace("\\", "/")
            # only interested in big or suspicious
            if (rel == "classes.dex" or rel.startswith("assets/") or rel.endswith((".dex",".so",".json",".dat",".bak",".spe",".sps",".bin",".prof",".profm")) or rel.startswith("META-INF/services/")):
                try:
                    info = scan_file(p)
                    sample[rel] = info
                    flag = "DEX" if info["is_dex"] else ("ELF" if info["is_elf"] else " ")
                    notes=[]
                    if info["urls"]:           notes.append(f"urls={len(info['urls'])}")
                    if info["domains"]:        notes.append(f"doms={len(info['domains'])}")
                    if info["ips"]:            notes.append(f"ips={len(info['ips'])}")
                    if info["telegram_bots"]:  notes.append(f"TG_BOT={info['telegram_bots']}")
                    if info["embedded_dex_offsets"]: notes.append(f"emb_dex@{info['embedded_dex_offsets']}")
                    if info["embedded_zip_offsets"] and not rel.endswith(".apk"): notes.append(f"emb_zip@{info['embedded_zip_offsets'][:3]}")
                    if info["keyword_counts"]: notes.append("kw=" + ",".join(f"{k}:{v}" for k,v in list(info["keyword_counts"].items())[:8]))
                    print(f"  [{flag}] E={info['entropy']:<6} sz={info['size']:>9}  {rel}   {'  '.join(notes)}")
                except Exception as e:
                    print(f"  ERR  {rel}: {e}")
                    sample[rel] = {"error": str(e)}
    ALL[d] = sample

with open(os.path.join(OUT, "07_realdex.json"), "w", encoding="utf-8") as f:
    json.dump(ALL, f, ensure_ascii=False, indent=2)
print(f"\n[+] Saved -> {OUT}\\07_realdex.json")
