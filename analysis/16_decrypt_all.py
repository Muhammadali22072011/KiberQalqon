"""Stage 16: Apply the discovered XOR+Base64 decryption algorithm to every banker APK,
auto-detect per-APK keys, and dump every plaintext string we can recover.
"""
import os, sys, io, json, re, base64, logging
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from loguru import logger
logger.remove()
logging.disable(logging.CRITICAL)
from androguard.misc import AnalyzeAPK

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
UNPACK = os.path.join(OUT, "unpacked")

def xor_bytes(data: bytes, key: bytes) -> bytes:
    return bytes(d ^ key[i % len(key)] for i, d in enumerate(data))

def decrypt_with_key(b64s: str, key: bytes) -> bytes:
    try:
        pad = (-len(b64s)) % 4
        raw = base64.b64decode(b64s + "=" * pad, validate=False)
    except Exception:
        return None
    return xor_bytes(raw, key)

def is_readable(b: bytes) -> bool:
    if not b or len(b) < 3: return False
    printable = sum(1 for c in b if 32 <= c < 127 or c in (9,10,13))
    return printable / len(b) > 0.85

def find_decrypt_methods(dx):
    """Find: (a) base method with hardcoded key  (b) wrapper that calls base + has 2 const-strings.
    Return: list of (method_full_name, list_of_literals, body_text)
    """
    res = []
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
            if "Base64;->decode" in body and "xor-int" in body:
                lits = re.findall(r'const-string [vp]\d+, "([^"]*)"', body)
                lits = [l for l in lits if l != ""]
                res.append((str(m.full_name), lits, body))
        except: pass
    return res

def auto_extract_keys(dx):
    """Return (base_key_bytes, top_key_bytes) automatically."""
    methods = find_decrypt_methods(dx)
    base_key = None
    top_key  = None
    base_method_lit_full = None
    wrapper_lits = None
    for full, lits, body in methods:
        # base method has 1 literal (and calls Base64 only, no other static deobf call)
        # wrapper has 2 literals AND calls another static method
        if len(lits) == 1 and "invoke-static" in body and "Base64;->decode" in body:
            # could be base method if NO other custom static call besides Base64
            # heuristic: count invoke-static for non-Base64 non-stdlib
            other_calls = [l for l in body.splitlines() if "invoke-static" in l and "Base64" not in l and "java/lang/String" not in l]
            if len(other_calls) <= 1:  # allow one possible log call
                if base_key is None:
                    base_key = lits[0].encode("utf-8")
                    print(f"  [base method] {full}  key={lits[0]!r}")
        if len(lits) >= 2:
            wrapper_lits = lits
            print(f"  [wrapper] {full}  lits={lits}")
    if base_key and wrapper_lits:
        # First literal of wrapper is base64-encoded encrypted XOR key
        outer_enc = wrapper_lits[0]
        decoded = decrypt_with_key(outer_enc, base_key)
        if decoded:
            try:
                top_key = decoded.decode("utf-8").encode("utf-8")
                print(f"  [derived top_key] {top_key!r}  len={len(top_key)}")
            except UnicodeDecodeError:
                # may have trailing junk; try shorter
                # try removing non-ascii tails
                clean = decoded
                # take only printable prefix
                cut = 0
                for i, c in enumerate(clean):
                    if 32 <= c < 127:
                        cut = i+1
                    else:
                        break
                if cut > 4:
                    top_key = clean[:cut]
                    print(f"  [derived top_key trunc] {top_key!r}  len={len(top_key)}")
    return base_key, top_key

apks = sorted([f for f in os.listdir(UNPACK) if f.endswith("_fixed.apk")])
all_decrypts = {}
for ap in apks:
    if "VIDEO" in ap: continue   # VIDEO uses native AES, different
    p = os.path.join(UNPACK, ap)
    name = ap.replace("_fixed.apk","")
    print(f"\n{'='*72}\n[+] {name}\n{'='*72}")
    a, d, dx = AnalyzeAPK(p)
    base_key, top_key = auto_extract_keys(dx)
    if not base_key:
        print("  [!] no base key detected, skipping")
        continue
    # Collect all Base64-shaped strings from this DEX
    strings = []
    for sv in dx.get_strings():
        try: strings.append(str(sv.get_value()))
        except: pass
    b64s = []
    pat = re.compile(r"^[A-Za-z0-9+/]{12,500}={0,2}$")
    for s in strings:
        if pat.match(s) and not re.fullmatch(r"[0-9a-fA-F]+", s):
            b64s.append(s)
    print(f"  candidates: {len(b64s)}")
    # Try BOTH base_key and top_key on each
    plaintexts = []
    for s in b64s:
        for key_label, key in (("top", top_key), ("base", base_key)):
            if key is None: continue
            pt = decrypt_with_key(s, key)
            if pt is None: continue
            if is_readable(pt):
                # store as decoded string
                try:
                    pt_str = pt.decode("utf-8", "replace")
                except:
                    pt_str = pt.decode("ascii", "replace")
                plaintexts.append({"ct": s, "key": key_label, "pt": pt_str})
                break
    # Dedup
    seen = set()
    unique = []
    for r in plaintexts:
        k = r["pt"]
        if k in seen: continue
        seen.add(k); unique.append(r)
    print(f"  decoded readable: {len(unique)}")

    # Categorize interesting
    juicy_pat = re.compile(r"(https?://|t\.me/|\.com\b|\.uz\b|\.ru\b|\.tg\b|\.xyz\b|\.online|\.top|\.cc\b|api\.tele|bot[A-Za-z0-9_]+|/sendMessage|/sendDocument|/upload|/dump|\d{8,}:[A-Za-z0-9_-]{30,}|uzcard|humo|click\.uz|payme|TBC|Alif|Anor|Ipak|Kapital|Hamkor|Asaka|Agrobank|Xalq|Infinbank|accessibilityservice|AccessibilityService)", re.IGNORECASE)
    juicy = [r for r in unique if juicy_pat.search(r["pt"])]
    print(f"  *** JUICY hits: {len(juicy)} ***")
    for r in juicy[:40]:
        print(f"    [{r['key']:>4}] {r['pt'][:200]}")
    all_decrypts[name] = {
        "base_key": base_key.decode("utf-8","replace") if base_key else None,
        "top_key":  top_key.decode("utf-8","replace") if top_key else None,
        "total_decoded": len(unique),
        "juicy": juicy,
        "all_plaintexts": unique,
    }

with open(os.path.join(OUT, "16_decrypts.json"), "w", encoding="utf-8") as f:
    json.dump(all_decrypts, f, ensure_ascii=False, indent=2)
print(f"\n[+] Saved -> {OUT}\\16_decrypts.json")
