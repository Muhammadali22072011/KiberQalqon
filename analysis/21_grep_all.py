"""Stage 21: Full grep over ALL decrypted plaintexts from every banker — print everything that looks like a URL/host/token/path/sensitive command."""
import json, os, sys, io, re
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"

with open(os.path.join(OUT, "16_decrypts.json"), encoding="utf-8") as f:
    data = json.load(f)

PATTERNS = [
    ("URL_HTTP",   re.compile(r"https?://[A-Za-z0-9._\-/%?=&:#~+@!]{4,250}")),
    ("URL_NOPROT", re.compile(r"\b[a-z0-9][a-z0-9\-]{2,40}\.(?:com|net|org|info|biz|ru|uz|xyz|top|club|site|online|store|shop|cc|me|tg|app|cyou|live|space|website|tk|ml|cf|ga|pw|bot|io|sbs|fun|click)\b(?:/[A-Za-z0-9._\-/%?=&:#~+@!]*)?", re.IGNORECASE)),
    ("TG_BOT_TOKEN", re.compile(r"\b\d{6,12}:[A-Za-z0-9_-]{30,60}\b")),
    ("TG_USERNAME",  re.compile(r"(?<![A-Za-z0-9_])@[A-Za-z][A-Za-z0-9_]{4,30}")),
    ("TG_CHAT_ID",   re.compile(r"-?100\d{8,12}")),
    ("PATH_API",     re.compile(r"/(?:api|cmd|send|upload|dump|exec|hook|panel|admin|bot|telegram|webhook)[/A-Za-z0-9_\-?=&]*")),
    ("IPv4",         re.compile(r"\b(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\b")),
    ("UZ_BANK",      re.compile(r"\b(?:uzcard|humo|click\.uz|payme|asaka|ipak|kapital|qishloq|agrobank|xalq|infinbank|hamkor|anor|tbc\.uz|alif|sqb|aab|tenge|davr|orient|capital)\b", re.IGNORECASE)),
    ("CMD",          re.compile(r"\b(?:get_sms|read_sms|send_sms|get_contacts|read_contacts|get_calls|installapp|uninstall|wipe|lock|admin|c2_server|bot_token|chat_id|exfil|get_card|cardnum|otp|password)\b", re.IGNORECASE)),
    ("SUS_DOMAIN",   re.compile(r"\b[A-Za-z][A-Za-z0-9\-]{2,40}\.(?:duckdns|no-ip|ddns|ngrok|amazonaws|cloudfront|herokuapp|vercel|netlify|onrender|fly\.dev)\.(?:org|com|net|app|io)?", re.IGNORECASE)),
]

GENERIC_NOISE = re.compile(r"(googleapis|googletagmanager|github|jetbrains|apache|adobe|w3\.org|kotlin\.io|java\.io|openai|chatgpt|gstatic|cloudflare|cdn-cgi|developer\.android|schemas\.android|firebaseio|crashlyt|sentry|youtrack|github\.io|GitHub-Mark|kotlinx_coroutines)", re.IGNORECASE)

for sample, info in data.items():
    print(f"\n{'='*72}\n[+] {sample}\n{'='*72}")
    plaintexts = [r["pt"] for r in info["all_plaintexts"]]
    print(f"  total decoded plaintexts: {len(plaintexts)}")
    for label, pat in PATTERNS:
        hits = set()
        for pt in plaintexts:
            for m in pat.findall(pt):
                if not GENERIC_NOISE.search(str(m)):
                    hits.add(m)
        if hits:
            print(f"\n  [{label}] {len(hits)} hits")
            for h in sorted(hits)[:50]:
                print(f"    {h}")
    # Also: any plaintext containing http or @ or :// or .uz/.ru/.tg
    print("\n  --- plaintexts mentioning http / .uz / .ru / .com / @ ---")
    seen=set()
    for pt in plaintexts:
        if any(k in pt.lower() for k in ("http", "://", ".uz/", ".uz ", ".uz\"", ".uz'", ".ru/", ".tg/", "@bot", "/sendMessage", "/sendDocument", "/sendPhoto", "telegram")):
            if pt not in seen and not GENERIC_NOISE.search(pt):
                seen.add(pt)
                print(f"    {pt[:240]}")
                if len(seen) > 50: break
