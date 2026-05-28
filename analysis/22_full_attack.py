"""Stage 22: Full-spectrum attack on the LARGE encrypted assets.
1. Known-plaintext XOR (try DEX/ZIP/ELF/HTML magic against head bytes — extract candidate key).
2. Use every plaintext discovered as a candidate XOR key.
3. Multi-stage XOR (base XOR top, top then base, etc).
4. AES with stretched keys (PBKDF2-like) on big files.
5. Brute random keys of 32 bytes from the decrypted plaintexts."""
import os, sys, io, re, json, hashlib, base64, struct
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from Crypto.Cipher import AES
from Crypto.Util import Counter

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
UNPACK = os.path.join(OUT, "unpacked")

KEYS = {
    "RASMLAR_18":          {"base": b"JYTAs0m31lxvwkQE42Y10Ktm",        "top": b"3183701586F97GhYNSURErMMwPeAS33H"},
    "RASMLAR_8":           {"base": b"UmmYqVRrK46tVxAG5PDVUI5rSF72a6p1", "top": b"551712693drkGqsgO05tb31vbvZGkA0P"},
    "toydanfotolar9jpg_10":{"base": b"AVbmxP9CNlQZRrzvnJhFw92n",         "top": b"347692886QFqc74H0w07DWz51sMHjqB4"},
    "VID_23856":           {"base": b"C49yA8hXYvs5cz7wGeEX7cQLTzYn",     "top": b"358200732kyGoz80aYtUropyXbvrXv9h"},
}

def find_key(sample_dir):
    for k, v in KEYS.items():
        if sample_dir.startswith(k[:20]): return v
    return None

def xor_bytes(data, key):
    return bytes(d ^ key[i % len(key)] for i, d in enumerate(data))

def score(pt):
    """How likely is this to be valid output?"""
    if not pt: return 0
    s = 0
    if pt[:5] == b"dex\n0":          s += 5000
    if pt[:4] == b"PK\x03\x04":      s += 5000
    if pt[:4] == b"\x7fELF":         s += 3000
    if pt[:2] == b"\x1f\x8b":        s += 2000
    if pt[:5] == b"<?xml":           s += 1500
    if pt[:1] in b"{[":              s += 500
    if pt[:1] in b"<":               s += 800
    sample = pt[:4096]
    printable = sum(1 for x in sample if 32 <= x < 127 or x in (9, 10, 13))
    s += int(printable / len(sample) * 1000)
    for kw in (b"http", b"://", b"telegram", b"bot", b"api.telegram",
               b"AccessibilityService", b"<manifest", b"android.permission",
               b"package=", b"/sendMessage", b".uz", b"public class", b"Lkotlin",
               b"Lcom/", b"Lretrofit", b"Lokhttp", b"<html"):
        if kw in sample: s += 300
    return s

def known_plaintext_attack(ct, known_pt):
    """Return candidate key as XOR of first len(known_pt) bytes."""
    return bytes(c ^ p for c, p in zip(ct[:len(known_pt)], known_pt))

# Known plaintext prefixes to try
KNOWN_PTS = [
    (b"dex\n035\x00", "DEX-035"),
    (b"dex\n036\x00", "DEX-036"),
    (b"dex\n037\x00", "DEX-037"),
    (b"dex\n038\x00", "DEX-038"),
    (b"dex\n039\x00", "DEX-039"),
    (b"PK\x03\x04\x14\x00\x08\x08\x08\x00", "ZIP_LFH"),
    (b"PK\x03\x04\x14\x00\x00\x00\x08\x00", "ZIP_LFH_v2"),
    (b"PK\x03\x04\x0a\x00\x00\x00\x00\x00", "ZIP_LFH_v3"),
    (b"\x7fELF\x02\x01\x01\x00", "ELF64"),
    (b"\x1f\x8b\x08\x00", "GZIP"),
    (b"<!DOCTYPE html>\n<html lang=\"ru\">\n<head>", "HTML_RU"),
    (b"<?xml version=\"1.0\" encoding=\"utf-8\"?>", "XML"),
    (b"{\"", "JSON"),
    (b"<manifest", "AndroidManifest"),
]

# Gather all decrypted plaintexts from 16_decrypts.json as candidate XOR keys
print("[+] Loading decrypted plaintexts from 16_decrypts.json")
with open(os.path.join(OUT, "16_decrypts.json"), encoding="utf-8") as f:
    decrypts = json.load(f)

PER_APK_PT_KEYS = {}
for sample, info in decrypts.items():
    pts = []
    for r in info["all_plaintexts"]:
        pt = r["pt"]
        if 8 <= len(pt) <= 128 and all(32 <= ord(c) < 127 for c in pt):
            pts.append(pt.encode("utf-8"))
    PER_APK_PT_KEYS[sample] = pts
    print(f"  {sample}: {len(pts)} plaintext key candidates")

LARGE = []
for sample_dir in os.listdir(UNPACK):
    full = os.path.join(UNPACK, sample_dir)
    if not os.path.isdir(full): continue
    keys = find_key(sample_dir)
    if not keys: continue
    assets = os.path.join(full, "assets")
    if not os.path.isdir(assets): continue
    for root, _, files in os.walk(assets):
        for fn in files:
            p = os.path.join(root, fn)
            sz = os.path.getsize(p)
            if sz < 100000: continue
            if fn.endswith((".spe",".sps",".data",".dat",".bak")):
                LARGE.append((sample_dir, p, fn, sz))

print(f"\n[+] {len(LARGE)} large encrypted assets to attack")
results = []

for sample_dir, p, fn, sz in LARGE:
    with open(p, "rb") as f:
        ct = f.read()
    print(f"\n{'='*72}\n[+] {sample_dir}  ::  {fn}  ({sz:,} bytes)\n{'='*72}")
    keys = find_key(sample_dir)
    print(f"  head: {ct[:32].hex()}")

    candidates = []

    # ---- Strategy 1: Known-plaintext attacks ----
    for pt, label in KNOWN_PTS:
        cand_key = known_plaintext_attack(ct, pt)
        # validate: re-xor the first len bytes — must give back pt
        check = xor_bytes(ct[:len(pt)], cand_key)
        if check != pt: continue
        # Now try this key on the whole file
        full = xor_bytes(ct, cand_key)
        s = score(full)
        if s >= 1000:
            candidates.append((s, f"KPA({label}) key_repeats_at={len(pt)}", cand_key, full))

    # Also try with KNOWN keys directly
    for key_label in ("base", "top"):
        k = keys.get(key_label)
        if not k: continue
        # plain XOR
        full = xor_bytes(ct, k)
        s = score(full)
        candidates.append((s, f"XOR_{key_label}", k, full))
        # SHA-256 stretched
        kk = hashlib.sha256(k).digest()
        full = xor_bytes(ct, kk)
        s = score(full)
        candidates.append((s, f"XOR_sha256({key_label})", kk, full))
        # MD5 stretched
        kk = hashlib.md5(k).digest()
        full = xor_bytes(ct, kk)
        s = score(full)
        candidates.append((s, f"XOR_md5({key_label})", kk, full))

    # combo top+base, base+top
    combo1 = (keys["base"] + keys["top"])
    combo2 = (keys["top"] + keys["base"])
    for kk, lbl in [(combo1, "concat(base+top)"), (combo2, "concat(top+base)")]:
        full = xor_bytes(ct, kk)
        s = score(full)
        candidates.append((s, f"XOR_{lbl}", kk, full))

    # XOR top with base bytewise
    if len(keys["top"]) and len(keys["base"]):
        L = max(len(keys["top"]), len(keys["base"]))
        a = (keys["top"] * (L // len(keys["top"]) + 1))[:L]
        b = (keys["base"] * (L // len(keys["base"]) + 1))[:L]
        xored = bytes(x ^ y for x, y in zip(a, b))
        full = xor_bytes(ct, xored)
        s = score(full)
        candidates.append((s, f"XOR_top^base", xored, full))

    # ---- Strategy 2: try every plaintext string as XOR key ----
    pt_keys = PER_APK_PT_KEYS.get(sample_dir, [])
    for pk in pt_keys[:300]:  # top 300 candidates
        if len(pk) < 8: continue
        full = xor_bytes(ct, pk)
        s = score(full)
        if s >= 1500:
            candidates.append((s, f"XOR_pt[{pk.decode('utf-8','replace')[:30]}...]", pk, full))

    # ---- Strategy 3: AES with top/base ----
    for key_label in ("top", "base"):
        k = keys.get(key_label)
        if not k: continue
        for klen in (16, 24, 32):
            if len(k) < klen: continue
            kk = k[:klen]
            # ECB
            body = ct[:len(ct) - (len(ct)%16)]
            try:
                full = AES.new(kk, AES.MODE_ECB).decrypt(body)
                candidates.append((score(full), f"AES-ECB key={key_label}[:{klen}]", kk, full))
            except: pass
            # CBC iv=ct[:16]
            try:
                body2 = ct[16:]; body2 = body2[:len(body2) - (len(body2)%16)]
                full = AES.new(kk, AES.MODE_CBC, ct[:16]).decrypt(body2)
                candidates.append((score(full), f"AES-CBC key={key_label}[:{klen}] iv=ct[:16]", kk, full))
            except: pass
        # SHA256 + ECB
        kk = hashlib.sha256(k).digest()
        body = ct[:len(ct) - (len(ct)%16)]
        try:
            full = AES.new(kk, AES.MODE_ECB).decrypt(body)
            candidates.append((score(full), f"AES-ECB sha256({key_label})", kk, full))
        except: pass

    # sort and show top
    candidates.sort(key=lambda x: -x[0])
    print(f"  total candidates: {len(candidates)}")
    for s, label, k, pt in candidates[:8]:
        print(f"    score={s:>5}  {label}")
        print(f"      head32: {pt[:32]!r}")
        # Print first readable line if any
        try:
            text = pt[:200].decode("utf-8", "replace")
            print(f"      text  : {text[:160]}")
        except: pass

    # Save top result if score is high
    if candidates and candidates[0][0] >= 1500:
        s, label, k, pt = candidates[0]
        out_p = os.path.join(OUT, f"FULLDEC_{sample_dir[:25]}_{fn}.bin")
        with open(out_p, "wb") as f:
            f.write(pt)
        print(f"  *** SAVED: {out_p} (score={s}, {label}) ***")
        results.append({"file": fn, "sample": sample_dir, "score": s, "method": label,
                        "key_hex": k.hex(), "key_preview": k[:64].decode("ascii","replace")})

with open(os.path.join(OUT, "22_full_attack.json"), "w", encoding="utf-8") as f:
    json.dump(results, f, ensure_ascii=False, indent=2)
print(f"\n[+] Total successful decrypts: {len(results)}")
print(f"[+] Saved manifest -> {OUT}\\22_full_attack.json")
