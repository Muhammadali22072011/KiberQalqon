"""Stage 19: Use discovered top_key (32 bytes = AES-256) on the LARGE encrypted assets."""
import os, sys, io, re, json, hashlib
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

def looks_useful(b):
    if not b: return 0
    sample = b[:4096]
    score = 0
    if b.startswith(b"PK\x03\x04"): score += 2000
    if b.startswith(b"dex\n0"): score += 2000
    if b.startswith(b"\x1f\x8b"): score += 800
    if b[:1] in (b"{", b"[", b"<"): score += 200
    printable = sum(1 for x in sample if 32 <= x < 127 or x in (9,10,13))
    if printable/len(sample) > 0.90: score += 500
    elif printable/len(sample) > 0.75: score += 100
    for kw in (b"http", b"://", b"telegram", b"bot", b"AccessibilityService",
               b"<manifest", b"android.permission", b"package=",
               b"api.telegram.org", b"/sendMessage", b".uz", b".com",
               b"public class", b"Lcom/", b"Lkotlin/", b"<html", b"<DOCTYPE",
               b"form", b"action", b"sms", b"contact", b"bank"):
        if kw in sample: score += 200
    return score

def try_modes(ct, key, label):
    hits = []
    # AES-ECB
    body = ct[:len(ct) - (len(ct)%16)]
    try:
        pt = AES.new(key, AES.MODE_ECB).decrypt(body)
        s = looks_useful(pt)
        hits.append((s, f"AES-ECB", pt))
    except: pass
    # AES-CBC IV=zeros
    try:
        pt = AES.new(key, AES.MODE_CBC, b"\x00"*16).decrypt(body)
        s = looks_useful(pt)
        hits.append((s, f"AES-CBC iv=0", pt))
    except: pass
    # AES-CBC IV=ct[:16]
    try:
        body2 = ct[16:]; body2 = body2[:len(body2) - (len(body2)%16)]
        pt = AES.new(key, AES.MODE_CBC, ct[:16]).decrypt(body2)
        s = looks_useful(pt)
        hits.append((s, f"AES-CBC iv=ct[:16] body=ct[16:]", pt))
    except: pass
    # AES-CTR
    try:
        ctr = Counter.new(128, initial_value=int.from_bytes(ct[:16], "big"))
        pt = AES.new(key, AES.MODE_CTR, counter=ctr).decrypt(ct[16:])
        s = looks_useful(pt)
        hits.append((s, f"AES-CTR nonce=ct[:16]", pt))
    except: pass
    # AES-GCM (last 16 = tag)
    try:
        tag = ct[-16:]; nonce = ct[:12]; body2 = ct[12:-16]
        pt = AES.new(key, AES.MODE_GCM, nonce=nonce).decrypt_and_verify(body2, tag)
        s = looks_useful(pt) + 500
        hits.append((s, f"AES-GCM nonce=ct[:12] tag=ct[-16:]", pt))
    except: pass
    return hits

def find_key_for_dir(sample_dir):
    for k, v in KEYS.items():
        if sample_dir.startswith(k[:20]):
            return v
    return None

for sample_dir in sorted(os.listdir(UNPACK)):
    full_dir = os.path.join(UNPACK, sample_dir)
    if not os.path.isdir(full_dir): continue
    keys = find_key_for_dir(sample_dir)
    if not keys: continue
    assets_dir = os.path.join(full_dir, "assets")
    if not os.path.isdir(assets_dir): continue
    print(f"\n{'='*72}\n[+] {sample_dir}\n{'='*72}")
    for root, _, files in os.walk(assets_dir):
        for fn in files:
            p = os.path.join(root, fn)
            sz = os.path.getsize(p)
            if sz < 100000:  # only LARGE assets (>100KB)
                continue
            if not fn.endswith((".spe",".sps",".data",".dat",".bak")): continue
            with open(p, "rb") as f:
                ct = f.read()
            print(f"\n  --- {fn} ({sz:,} bytes) ---")
            # Build key candidates: top, top sha256, base sha256, top+base
            key_candidates = [
                ("top_32",       keys["top"][:32] if len(keys["top"])>=32 else keys["top"].ljust(32, b'\0')),
                ("top_16",       keys["top"][:16]),
                ("top_24",       keys["top"][:24]),
                ("base_pad32",   (keys["base"] + b"\0"*32)[:32]),
                ("base_pad16",   (keys["base"] + b"\0"*16)[:16]),
                ("sha256_top",   hashlib.sha256(keys["top"]).digest()),
                ("sha256_base",  hashlib.sha256(keys["base"]).digest()),
                ("md5_top",      hashlib.md5(keys["top"]).digest()),
                ("md5_base",     hashlib.md5(keys["base"]).digest()),
                ("top_xor_base", bytes(a^b for a,b in zip(keys["top"], (keys["base"]*4)[:32]))),
                ("concat_b+t_32",(keys["base"]+keys["top"])[:32]),
                ("concat_t+b_32",(keys["top"]+keys["base"])[:32]),
            ]
            best = []
            for kname, k in key_candidates:
                if len(k) not in (16, 24, 32): continue
                for score, label, pt in try_modes(ct, k, kname):
                    if score > 0:
                        best.append((score, kname, label, pt))
            best.sort(key=lambda x: -x[0])
            for score, kname, label, pt in best[:5]:
                print(f"    score={score:>5}  key={kname:<14}  {label}")
                print(f"      head: {pt[:64]}")
            # Save the best one if score > 1000
            if best and best[0][0] >= 1000:
                score, kname, label, pt = best[0]
                outp = os.path.join(OUT, f"DECRYPTED_AES_{sample_dir[:25]}_{fn}.bin")
                with open(outp, "wb") as f:
                    f.write(pt)
                print(f"    *** SAVED: {outp} ***")
            elif best and best[0][0] >= 300:
                score, kname, label, pt = best[0]
                outp = os.path.join(OUT, f"TRY_AES_{sample_dir[:25]}_{fn}.bin")
                with open(outp, "wb") as f:
                    f.write(pt)
                print(f"    (saved trial: {outp})")
