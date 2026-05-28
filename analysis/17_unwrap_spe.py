"""Stage 17: Apply discovered XOR keys to .spe / .sps / .data / .bak encrypted assets,
and dump ALL decrypted strings to grep through.
"""
import os, sys, io, json, base64, re, zipfile, hashlib
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
UNPACK = os.path.join(OUT, "unpacked")

# Keys we discovered
KEYS = {
    "RASMLAR_18": {
        "base": b"JYTAs0m31lxvwkQE42Y10Ktm",
        "top":  b"3183701586F97GhYNSURErMMwPeAS33H",
    },
    "RASMLAR_8": {
        "base": b"UmmYqVRrK46tVxAG5PDVUI5rSF72a6p1",
        "top":  b"551712693drkGqsgO05tb31vbvZGkA0P",
    },
    "toydanfotolar9jpg_10": {
        "base": b"AVbmxP9CNlQZRrzvnJhFw92n",
        "top":  b"347692886QFqc74H0w07DWz51sMHjqB4",
    },
    "VID_23856_21052026ㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤ": {
        "base": b"C49yA8hXYvs5cz7wGeEX7cQLTzYn",
        "top":  b"358200732kyGoz80aYtUropyXbvrXv9h",
    },
}

def xor_bytes(data, key):
    return bytes(d ^ key[i % len(key)] for i, d in enumerate(data))

def strings(b, minlen=6):
    return [m.decode("ascii","ignore") for m in re.findall(rb"[\x20-\x7e]{%d,}" % minlen, b)]

URL_PAT = re.compile(r"https?://[A-Za-z0-9._\-/%?=&:#~+]{4,200}")
TG_BOT_PAT = re.compile(r"\b\d{6,12}:[A-Za-z0-9_-]{30,60}\b")
TG_USER_PAT = re.compile(r"@[A-Za-z][A-Za-z0-9_]{3,30}")
TG_CHAT_PAT = re.compile(r"-?100\d{8,12}")
DOM_PAT = re.compile(r"\b(?=[A-Za-z0-9-]{1,63}\.)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+(?:com|net|org|info|biz|ru|uz|xyz|top|club|site|online|store|shop|cc|me|tg|in|app|cyou|live|space|website|tk|ml|cf|ga|pw|bot|io|sbs|fun|click)\b", re.IGNORECASE)
IP_PAT = re.compile(r"\b(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\b")

SKIP_DOM = re.compile(r"(google|jetbrains|apache|adobe|github|w3\.org|kotlinx|openai|githubassets|kotlin\.io|java\.io|chatgpt)", re.IGNORECASE)

def is_juicy(s):
    return ("http" in s.lower() or "t.me/" in s.lower() or "telegram" in s.lower()
            or "://" in s or "/api/" in s or "/send" in s or "/dump" in s or "/upload" in s
            or re.search(r"\b\d{8,}:[A-Za-z0-9_-]{30,}", s)
            or any(k in s.lower() for k in ("bot_token","botid","accessibility","uzcard","humo","click.uz","payme","asaka","ipak","tbc.uz","alif","anor","kapital","xalq","agrobank","hamkor","qishloq")))

# Sub-step 1: print ALL decrypted DEX strings (filtered for length)
with open(os.path.join(OUT, "16_decrypts.json"), "r", encoding="utf-8") as f:
    decrypts = json.load(f)

for sample, info in decrypts.items():
    print(f"\n{'='*72}\n[+] {sample}  base={info.get('base_key')}  top={info.get('top_key')}\n{'='*72}")
    pts = [r["pt"] for r in info["all_plaintexts"]]
    # Scan plaintexts
    urls = set(); doms = set(); ips = set(); bots = set(); chats = set(); users = set()
    for pt in pts:
        for m in URL_PAT.findall(pt): urls.add(m)
        for m in DOM_PAT.findall(pt):
            if not SKIP_DOM.search(m): doms.add(m)
        for m in IP_PAT.findall(pt):
            if not m.startswith(("127.","255.","0.","10.","192.168.","169.254.","2.5.","1.3.","1.10")): ips.add(m)
        for m in TG_BOT_PAT.findall(pt): bots.add(m)
        for m in TG_CHAT_PAT.findall(pt): chats.add(m)
        for m in TG_USER_PAT.findall(pt): users.add(m)
    print(f"  URLs:{len(urls)}  domains:{len(doms)}  IPs:{len(ips)}  TG_BOTS:{len(bots)}  TG_USERS:{len(users)}  TG_CHATS:{len(chats)}")
    for x in sorted(urls):  print(f"    URL  : {x}")
    for x in sorted(doms):  print(f"    DOM  : {x}")
    for x in sorted(ips):   print(f"    IP   : {x}")
    for x in sorted(bots):  print(f"    TGBOT: {x}")
    for x in sorted(users): print(f"    USER : {x}")
    for x in sorted(chats): print(f"    CHAT : {x}")
    # Show plaintexts containing "://" or "telegram" or "bot"
    print("  --- juicy plaintexts ---")
    juicy_pts = [p for p in pts if is_juicy(p)]
    for p in juicy_pts[:50]:
        print(f"    {p[:240]}")

# Sub-step 2: try XOR-decrypting the encrypted assets with each key
print("\n" + "="*72)
print("[+] Trying XOR decrypt on encrypted assets (.spe, .sps, .data, .bak)")
print("="*72)
for sample_dir in os.listdir(UNPACK):
    full_dir = os.path.join(UNPACK, sample_dir)
    if not os.path.isdir(full_dir): continue
    keys = KEYS.get(sample_dir)
    if not keys:
        # try fuzzy match (unicode chars)
        for k, v in KEYS.items():
            if sample_dir.startswith(k[:20]):
                keys = v; break
    if not keys:
        continue
    assets_dir = os.path.join(full_dir, "assets")
    if not os.path.isdir(assets_dir): continue
    print(f"\n----- {sample_dir} -----")
    for root, _, files in os.walk(assets_dir):
        for fn in files:
            if not fn.endswith((".spe",".sps",".data",".dat",".bak")): continue
            p = os.path.join(root, fn)
            sz = os.path.getsize(p)
            with open(p, "rb") as f:
                ct = f.read()
            # try each key
            for kname, k in keys.items():
                pt = xor_bytes(ct, k)
                # Check: is it a DEX? ZIP? readable?
                header = pt[:16]
                ok = False
                if pt.startswith(b"dex\n0"): label = "DEX"; ok = True
                elif pt.startswith(b"PK\x03\x04"): label = "ZIP/APK"; ok = True
                elif pt.startswith(b"\x1f\x8b"): label = "GZIP"; ok = True
                elif pt[:4] == b"{\"" or pt[:1] in b"{[": label = "JSON"; ok = True
                else:
                    # check printable ratio
                    sample = pt[:1024]
                    printable = sum(1 for c in sample if 32 <= c < 127 or c in (9,10,13))
                    if printable/len(sample) > 0.85:
                        label = "TEXT"; ok = True
                    else:
                        label = None
                if ok:
                    print(f"  ✔ {fn} sz={sz}  key={kname}  -> {label}  head: {header.hex()}")
                    out_path = os.path.join(OUT, f"DECRYPTED_{sample_dir[:30]}_{fn}.bin")
                    with open(out_path, "wb") as f:
                        f.write(pt)
                    print(f"     saved -> {out_path}")
                    # if text/json, show first lines
                    if label in ("TEXT","JSON"):
                        try:
                            text = pt[:500].decode("utf-8","replace")
                            print(f"     preview: {text}")
                        except: pass
                else:
                    print(f"  ✘ {fn} key={kname}  head: {header.hex()}  (no match)")
