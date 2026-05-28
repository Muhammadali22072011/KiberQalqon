"""Stage 18: Grep decrypted HTML overlay pages for URLs, bot tokens, exfil endpoints."""
import os, sys, io, re, json
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"

URL_PAT      = re.compile(r"https?://[A-Za-z0-9._\-/%?=&:#~+@!]{4,250}")
TG_BOT_PAT   = re.compile(r"\b\d{6,12}:[A-Za-z0-9_-]{30,60}\b")
TG_USER_PAT  = re.compile(r"@[A-Za-z][A-Za-z0-9_]{3,30}")
TG_CHAT_PAT  = re.compile(r"-?100\d{8,12}")
FORM_PAT     = re.compile(r"""<form[^>]*action\s*=\s*["']([^"']+)["']""", re.IGNORECASE)
FETCH_PAT    = re.compile(r"""(?:fetch|XMLHttpRequest|\.open\s*\()\s*\(?\s*["']([A-Z]+)?["']?\s*,?\s*["']([^"']+)["']""", re.IGNORECASE)
AJAX_URL_PAT = re.compile(r"""(?:url|endpoint|api|server|host|target|action|exfil|bot|webhook)\s*[:=]\s*["']([^"']+)["']""", re.IGNORECASE)
SCRIPT_VAR   = re.compile(r"""(?:var|let|const)\s+[A-Za-z_][\w]*\s*=\s*["']([^"']+)["']""")

ALL = {}
for fn in sorted(os.listdir(OUT)):
    if not fn.startswith("DECRYPTED_") or not fn.endswith(".bin"): continue
    p = os.path.join(OUT, fn)
    with open(p, "rb") as f:
        data = f.read()
    try:
        text = data.decode("utf-8", "replace")
    except:
        text = data.decode("latin1", "replace")
    print(f"\n{'='*72}\n[+] {fn}  size={len(data):,}\n{'='*72}")

    urls    = sorted(set(URL_PAT.findall(text)))
    bots    = sorted(set(TG_BOT_PAT.findall(text)))
    users   = sorted(set(TG_USER_PAT.findall(text)))
    chats   = sorted(set(TG_CHAT_PAT.findall(text)))
    actions = sorted(set(FORM_PAT.findall(text)))
    fetches = sorted(set(m[1] for m in FETCH_PAT.findall(text)))
    vars_   = sorted(set(AJAX_URL_PAT.findall(text)))

    print(f"  URLs:{len(urls)}  TG_BOTS:{len(bots)}  TG_USERS:{len(users)}  TG_CHATS:{len(chats)}")
    for u in urls:        print(f"    URL    : {u}")
    for u in bots:        print(f"    TG_BOT : {u}")
    for u in users:       print(f"    TG_USER: {u}")
    for u in chats:       print(f"    TG_CHAT: {u}")
    for u in actions:     print(f"    FORM   : {u}")
    for u in fetches:     print(f"    FETCH  : {u}")
    for u in vars_:       print(f"    VAR_URL: {u}")

    # Dump <script> block bodies — full
    scripts = re.findall(r"<script[^>]*>(.*?)</script>", text, re.IGNORECASE | re.DOTALL)
    print(f"  --- {len(scripts)} <script> blocks ---")
    for i, sc in enumerate(scripts):
        sc = sc.strip()
        if not sc: continue
        # show first 1500 chars
        print(f"  [script #{i+1}] len={len(sc)}")
        for line in sc.splitlines()[:60]:
            line = line.strip()
            if line: print(f"      {line}")

    ALL[fn] = {
        "size": len(data),
        "urls": urls, "tg_bots": bots, "tg_users": users, "tg_chats": chats,
        "form_actions": actions, "fetch_urls": fetches, "vars": vars_,
    }

with open(os.path.join(OUT, "18_html_ioc.json"), "w", encoding="utf-8") as f:
    json.dump(ALL, f, ensure_ascii=False, indent=2)
print(f"\n[+] Saved -> {OUT}\\18_html_ioc.json")
