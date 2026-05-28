"""Stage 24: Final desperation attacks on big encrypted files."""
import os, sys, io, re, hashlib
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from Crypto.Cipher import AES

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
UNPACK = os.path.join(OUT, "unpacked")

KEYS = {
    "RASMLAR_18":          {"base": b"JYTAs0m31lxvwkQE42Y10Ktm",        "top": b"3183701586F97GhYNSURErMMwPeAS33H", "pkg": b"com.lzthzvxte.xazoalzxhr", "ipkg": b"vpvaggzk.com"},
    "RASMLAR_8":           {"base": b"UmmYqVRrK46tVxAG5PDVUI5rSF72a6p1", "top": b"551712693drkGqsgO05tb31vbvZGkA0P", "pkg": b"com.oktgkst.rrcpkge",    "ipkg": b"fysrzn.com"},
    "toydanfotolar9jpg_10":{"base": b"AVbmxP9CNlQZRrzvnJhFw92n",         "top": b"347692886QFqc74H0w07DWz51sMHjqB4", "pkg": b"com.yzsfnie.sjsztphpis","ipkg": b"ujsfgsyu.com"},
    "VID_23856":           {"base": b"C49yA8hXYvs5cz7wGeEX7cQLTzYn",     "top": b"358200732kyGoz80aYtUropyXbvrXv9h", "pkg": b"com.puhfvysb.nzbftunmqq","ipkg": b"zrtssr.com"},
}

def find_keys(d):
    for k, v in KEYS.items():
        if d.startswith(k[:18]): return v
    return None

def xor(a, k): return bytes(b ^ k[i % len(k)] for i, b in enumerate(a))

def isgood(b):
    if not b: return 0
    s = 0
    if b[:5] == b"dex\n0":      s += 5000
    if b[:4] == b"PK\x03\x04":  s += 5000
    if b[:4] == b"\x7fELF":     s += 3000
    if b[:2] == b"\x1f\x8b":    s += 2000
    if b[:5] == b"<?xml":       s += 2000
    if b[:1] in b"{[<":         s += 800
    sample = b[:4096]
    printable = sum(1 for x in sample if 32 <= x < 127 or x in (9,10,13))
    s += int(printable / len(sample) * 1500)
    for kw in (b"http", b"://", b"telegram", b"bot", b"api.telegram", b"AccessibilityService",
               b"<manifest", b"android.permission", b"package=", b"/sendMessage",
               b".uz", b".ru", b"public class", b"<html", b"function"):
        if kw in sample: s += 400
    return s

for sample_dir in sorted(os.listdir(UNPACK)):
    fd = os.path.join(UNPACK, sample_dir)
    if not os.path.isdir(fd): continue
    keys = find_keys(sample_dir)
    if not keys: continue
    assets = os.path.join(fd, "assets")
    if not os.path.isdir(assets): continue
    # collect all asset files
    asset_files = {}
    for root, _, files in os.walk(assets):
        for fn in files:
            if fn.endswith((".spe", ".sps", ".data", ".bak", ".dat")):
                p = os.path.join(root, fn)
                with open(p, "rb") as f:
                    asset_files[fn] = f.read()
    if not asset_files: continue
    print(f"\n{'='*72}\n[+] {sample_dir}\n{'='*72}")
    smalls = {n: d for n, d in asset_files.items() if len(d) < 50000}
    bigs = {n: d for n, d in asset_files.items() if len(d) >= 100000}
    print(f"  smalls: {list(smalls)}\n  bigs:   {list(bigs)}")
    print(f"  available keys: base/top/pkg/ipkg + size{{16,24,32,64}} stretched")
    for bname, bdata in bigs.items():
        print(f"\n  >>> attacking {bname} ({len(bdata):,} bytes)")
        # Build a HUGE key candidate list
        cands = []
        for kname, k in keys.items():
            cands.append((kname, k))
            cands.append((f"sha256({kname})", hashlib.sha256(k).digest()))
            cands.append((f"md5({kname})", hashlib.md5(k).digest()))
            cands.append((f"sha1({kname})", hashlib.sha1(k).digest()))
        cands.append(("top+base", keys["top"]+keys["base"]))
        cands.append(("base+top", keys["base"]+keys["top"]))
        cands.append(("base^top", bytes(a^b for a,b in zip((keys["base"]*4)[:32], (keys["top"]*4)[:32]))))
        cands.append(("top+pkg", keys["top"]+keys["pkg"]))
        cands.append(("top+ipkg", keys["top"]+keys["ipkg"]))
        cands.append(("sha256(top+base)", hashlib.sha256(keys["top"]+keys["base"]).digest()))
        cands.append(("sha256(base+top)", hashlib.sha256(keys["base"]+keys["top"]).digest()))
        cands.append(("sha256(top+ipkg)", hashlib.sha256(keys["top"]+keys["ipkg"]).digest()))
        # use small file CONTENT (encrypted) as key
        for sn, sd in smalls.items():
            cands.append((f"raw({sn})", sd))
            cands.append((f"sha256({sn})", hashlib.sha256(sd).digest()))
            cands.append((f"first32({sn})", sd[:32]))
            cands.append((f"last32({sn})", sd[-32:]))
        # use decrypted small file content as key
        for sn, sd in smalls.items():
            pt = xor(sd, keys["top"])
            cands.append((f"decrypted({sn})", pt))
            cands.append((f"decrypted({sn})[:64]", pt[:64]))
            cands.append((f"sha256(decrypted({sn}))", hashlib.sha256(pt).digest()))
        # use big file's own first N bytes as key (offsets 0/16/32)
        for off, plen in [(0,16),(0,32),(0,64),(0,128),(0,256),(16,16),(16,32),(16,64),(32,16),(32,32)]:
            k = bdata[off:off+plen]
            body_start = off + plen
            pt = xor(bdata[body_start:], k)
            cands.append((f"self_prefix off={off} len={plen}", k, pt, body_start))
        # Try each as XOR key
        results = []
        for c in cands:
            if len(c) == 2:
                label, key = c
                if not key: continue
                pt = xor(bdata, key)
                results.append((isgood(pt), label, pt, 0))
            else:
                label, key, pt, off = c
                results.append((isgood(pt), label, pt, off))
        results.sort(key=lambda x: -x[0])
        for sc, label, pt, off in results[:10]:
            text = pt[:200].decode("utf-8","replace")
            print(f"    sc={sc:>5}  {label}")
            print(f"      head: {pt[:32]}")
            print(f"      text: {text[:120]}")
        # save best if score > 2500
        best = results[0]
        if best[0] >= 3000:
            sc, label, pt, off = best
            outp = os.path.join(OUT, f"WIN_{sample_dir[:25]}_{bname}.bin")
            with open(outp, "wb") as f:
                f.write(pt)
            print(f"    *** BIG WIN: {outp} ({label}) ***")
        # AES with all the candidate keys (length 16/24/32)
        print(f"    --- AES sweep ---")
        aes_results = []
        for c in cands[:60]:
            label = c[0]; key = c[1] if len(c)==2 else c[1]
            for klen in (16, 24, 32):
                if len(key) < klen: continue
                kk = key[:klen]
                # ECB
                try:
                    body = bdata[:len(bdata)-(len(bdata)%16)]
                    pt = AES.new(kk, AES.MODE_ECB).decrypt(body)
                    aes_results.append((isgood(pt), f"AES-ECB key={label}[:{klen}]", pt))
                except: pass
                # CBC zeros
                try:
                    body = bdata[:len(bdata)-(len(bdata)%16)]
                    pt = AES.new(kk, AES.MODE_CBC, b"\0"*16).decrypt(body)
                    aes_results.append((isgood(pt), f"AES-CBC iv=0 key={label}[:{klen}]", pt))
                except: pass
                # CBC iv=ct[:16]
                try:
                    body = bdata[16:]; body = body[:len(body)-(len(body)%16)]
                    pt = AES.new(kk, AES.MODE_CBC, bdata[:16]).decrypt(body)
                    aes_results.append((isgood(pt), f"AES-CBC iv=ct[:16] key={label}[:{klen}]", pt))
                except: pass
        aes_results.sort(key=lambda x: -x[0])
        for sc, label, pt in aes_results[:5]:
            if sc > 1500:
                text = pt[:200].decode("utf-8","replace")
                print(f"    AES sc={sc:>5}  {label}")
                print(f"        head: {pt[:32]}  text: {text[:120]}")
                if sc > 3000:
                    outp = os.path.join(OUT, f"WIN_AES_{sample_dir[:25]}_{bname}.bin")
                    with open(outp, "wb") as f:
                        f.write(pt)
                    print(f"        *** AES WIN: {outp} ***")
