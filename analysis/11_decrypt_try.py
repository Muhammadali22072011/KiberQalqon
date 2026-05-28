"""Stage 11: Try XOR-decrypting Base64 strings with candidate keys."""
import os, sys, io, json, base64, re, logging
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from loguru import logger
logger.remove()
logging.disable(logging.CRITICAL)

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"

with open(os.path.join(OUT, "10_ioc_strings.json"), "r", encoding="utf-8") as f:
    data = json.load(f)

# Per-sample candidate XOR keys (plus generic/short keys)
SAMPLE_KEYS = {
    "RASMLAR_18":           ["xzpkbotsff"],
    "RASMLAR_8":            ["botjbbqyftfgpfk", "poqbotmf"],
    "toydanfotolar9jpg_10": ["qbvsybotru"],
}
GENERIC_KEYS = [
    "bot", "key", "1234567890",
    "vpvaggzk.com", "fysrzn.com", "ujsfgsyu.com", "zrtssr.com", "ydbllnjd.com",
]

def xor(b: bytes, key: bytes) -> bytes:
    return bytes(c ^ key[i % len(key)] for i, c in enumerate(b))

def is_readable(s: bytes) -> bool:
    # Mostly printable ASCII or extended UTF-8?
    if len(s) < 6: return False
    printable = sum(1 for x in s if 32 <= x < 127 or x in (9, 10, 13))
    return printable / len(s) > 0.85

def looks_juicy(s: str) -> bool:
    keys = ["http", "://", "t.me/", "telegram", "api.tele", "bot", "token", "chat_id",
            "uzcard", "humo", ".uz", "click.uz", "payme", "asaka", "ipak", "tbc",
            "AccessibilityService", "BIND_ACC", "android.permission", "RECEIVE_SMS",
            "@gmail", "ngrok", "duckdns", "amazonaws", "cloudfront", "/api/", "/cmd",
            "/send", "/dump", "/upload"]
    sl = s.lower()
    return any(k in sl for k in keys)

found_total = 0
for sample, info in data.items():
    keys = SAMPLE_KEYS.get(sample, []) + GENERIC_KEYS
    b64s = info.get("base64_encrypted_strings", [])
    print(f"\n{'='*72}\n[+] {sample}  candidate-keys={keys[:5]}...  encrypted_count={len(b64s)}\n{'='*72}")
    sample_hits = []
    # Try first 80 longest strings (already sorted by length desc)
    for s in b64s[:120]:
        # Base64 decode
        try:
            # pad to multiple of 4
            pad = (-len(s)) % 4
            raw = base64.b64decode(s + "=" * pad, validate=False)
        except Exception:
            continue
        if len(raw) < 6: continue
        for k in keys:
            kb = k.encode("utf-8")
            dec = xor(raw, kb)
            if is_readable(dec):
                txt = dec.decode("ascii", "ignore")
                # Filter to readable English-like
                if re.fullmatch(r"[\x20-\x7e\s]+", txt) and len(txt) >= 8:
                    juicy = looks_juicy(txt)
                    sample_hits.append({"key": k, "ct": s[:64], "pt": txt, "juicy": juicy})
                    marker = " *** JUICY ***" if juicy else ""
                    print(f"  KEY='{k}'{marker}  PT: {txt[:200]}")
                    break  # one hit per ciphertext is enough
    found_total += len(sample_hits)

print(f"\n[+] total hits: {found_total}")
