"""Stage 14: Retry AES decrypt with corrected 32-byte key + extract decrypt routine from banker DEX."""
import os, sys, io, re, struct, hashlib, logging
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from Crypto.Cipher import AES
from Crypto.Util import Counter
from loguru import logger
logger.remove()
logging.disable(logging.CRITICAL)
from androguard.misc import AnalyzeAPK

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
UNPACK = os.path.join(OUT, "unpacked")

# ============== Part 1: AES on cvsmcn.json with correct 32-byte key ==============
PAYLOAD = os.path.join(UNPACK, "VIDEO20012026mp4_2", "assets", "cvsmcn.json")
with open(PAYLOAD, "rb") as f:
    ct = f.read()

# Correct key candidates (the 30/31/32 byte split possibilities)
RAW = b"sqsmlH2NOLPXeaDIFGnMEOdG6Uc2mVingetSecretKey"
print(f"[+] Raw string: {RAW!r}  len={len(RAW)}")
KEYS = [
    ("k32_first32",   RAW[:32]),                 # 'sqsmlH2NOLPXeaDIFGnMEOdG6Uc2mVin'
    ("k32_pad",       (b"sqsmlH2NOLPXeaDIFGnMEOdG6Uc2mV" + b"\0\0")),
    ("k24",           RAW[:24]),
    ("k16",           RAW[:16]),
    ("kfull44",       RAW[:32]),
    ("sha256_str",    hashlib.sha256(RAW[:32]).digest()),
    ("md5_str",       hashlib.md5(RAW[:32]).digest()),
]

def looks_useful(b):
    if not b: return 0
    sample = b[:4096]
    score = 0
    # check for ZIP / DEX / APK headers
    if b.startswith(b"PK\x03\x04"): score += 1000
    if b.startswith(b"dex\n0"): score += 1000
    if b.startswith(b"\x1f\x8b"): score += 500  # gzip
    if b.startswith(b"{") or b.startswith(b"["): score += 200  # json
    # english/printable ratio
    printable = sum(1 for x in sample if 32 <= x < 127 or x in (9,10,13))
    if printable/len(sample) > 0.95: score += 200
    elif printable/len(sample) > 0.80: score += 50
    # keyword hits
    for kw in (b"http", b"://", b"telegram", b"bot", b"AccessibilityService",
               b"<manifest", b"<application", b"android.permission", b"package=",
               b"https://api.telegram.org", b"/sendMessage", b".uz", b".com",
               b"public class", b"private", b"Lcom/", b"Lkotlin/"):
        if kw in sample: score += 100
    return score

best_video = []
for kname, k in KEYS:
    if len(k) not in (16, 24, 32): continue
    # 1) Plain AES-ECB
    try:
        body = ct[:len(ct) - (len(ct)%16)]
        pt = AES.new(k, AES.MODE_ECB).decrypt(body)
        s = looks_useful(pt)
        best_video.append((s, f"ECB key={kname}", pt[:200]))
    except: pass
    # 2) CBC with IV = first 16 of ciphertext, body = ct[16:]
    try:
        body = ct[16:]; body = body[:len(body) - (len(body)%16)]
        pt = AES.new(k, AES.MODE_CBC, ct[:16]).decrypt(body)
        s = looks_useful(pt)
        best_video.append((s, f"CBC key={kname} iv=ct[:16]", pt[:200]))
    except: pass
    # 3) CBC IV=zeros
    try:
        body = ct[:len(ct) - (len(ct)%16)]
        pt = AES.new(k, AES.MODE_CBC, b"\x00"*16).decrypt(body)
        s = looks_useful(pt)
        best_video.append((s, f"CBC key={kname} iv=zeros", pt[:200]))
    except: pass
    # 4) CTR
    try:
        ctr = Counter.new(128, initial_value=int.from_bytes(ct[:16], "big"))
        pt = AES.new(k, AES.MODE_CTR, counter=ctr).decrypt(ct[16:])
        s = looks_useful(pt)
        best_video.append((s, f"CTR key={kname} nonce=ct[:16]", pt[:200]))
    except: pass
    # 5) GCM (tag at end?)
    try:
        tag = ct[-16:]; nonce = ct[:12]; body = ct[12:-16]
        pt = AES.new(k, AES.MODE_GCM, nonce=nonce).decrypt_and_verify(body, tag)
        s = looks_useful(pt) + 500
        best_video.append((s, f"GCM key={kname} nonce=ct[:12] tag=ct[-16:]", pt[:200]))
    except: pass

best_video.sort(key=lambda x: -x[0])
print("\n[+] VIDEO.mp4 cvsmcn.json best decryption attempts:")
for s, label, pt in best_video[:15]:
    print(f"  score={s:>5}  {label}")
    print(f"    pt: {pt!r}")

# Save the best one if score > 200
if best_video and best_video[0][0] > 200:
    s, label, _ = best_video[0]
    # reproduce
    parts = label.split()
    mode = parts[0]
    kname = [p for p in parts if p.startswith("key=")][0].split("=",1)[1]
    k = dict(KEYS)[kname]
    if mode == "ECB":
        full = AES.new(k, AES.MODE_ECB).decrypt(ct[:len(ct) - (len(ct)%16)])
    elif mode == "CBC":
        if "iv=ct[:16]" in label:
            body = ct[16:]; body = body[:len(body) - (len(body)%16)]
            full = AES.new(k, AES.MODE_CBC, ct[:16]).decrypt(body)
        else:
            body = ct[:len(ct) - (len(ct)%16)]
            full = AES.new(k, AES.MODE_CBC, b"\x00"*16).decrypt(body)
    elif mode == "CTR":
        ctr = Counter.new(128, initial_value=int.from_bytes(ct[:16], "big"))
        full = AES.new(k, AES.MODE_CTR, counter=ctr).decrypt(ct[16:])
    elif mode == "GCM":
        full = AES.new(k, AES.MODE_GCM, nonce=ct[:12]).decrypt_and_verify(ct[12:-16], ct[-16:])
    out_path = os.path.join(OUT, "VIDEO_cvsmcn_decrypted.bin")
    with open(out_path, "wb") as f:
        f.write(full)
    print(f"\n[+] Saved best decrypt -> {out_path}  size={len(full):,}")

# ============== Part 2: find Cipher decrypt routine in banker DEX ==============
print("\n" + "="*72)
print("[+] Searching banker DEX for Cipher.doFinal / Base64.decode methods")
print("="*72)

bankers = ["RASMLAR_18_fixed.apk", "RASMLAR_8_fixed.apk",
           "toydanfotolar9jpg_10_fixed.apk"]
for f in os.listdir(UNPACK):
    if f.startswith("VID_23856") and f.endswith("_fixed.apk"):
        bankers.append(f)

for bk in bankers:
    apk = os.path.join(UNPACK, bk)
    if not os.path.isfile(apk): continue
    print(f"\n----- {bk} -----")
    a, d, dx = AnalyzeAPK(apk)
    cipher_callers = []
    for m in dx.get_methods():
        try:
            if m.is_external(): continue
            method = m.get_method()
            if not method or not method.get_code(): continue
            ins_text = []
            for ins in method.get_instructions():
                try:
                    ins_text.append(ins.get_name() + " " + ins.get_output())
                except: pass
            full = "\n".join(ins_text)
            has_cipher = "javax/crypto/Cipher" in full
            has_b64    = "Base64" in full and "decode" in full
            if has_cipher or has_b64:
                cipher_callers.append((str(m.full_name), has_cipher, has_b64, full))
        except: pass
    print(f"  found {len(cipher_callers)} candidate methods")
    # Print top 3 most relevant (cipher + b64)
    cipher_callers.sort(key=lambda x: -(x[1]*2 + x[2]))
    for name, hc, hb, body in cipher_callers[:5]:
        print(f"\n  ----- {name}  cipher={hc} b64={hb} -----")
        for line in body.splitlines()[:60]:
            print(f"    {line}")
