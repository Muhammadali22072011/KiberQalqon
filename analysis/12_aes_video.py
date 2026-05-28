"""Stage 12: Brute AES on VIDEO.mp4 payload `cvsmcn.json` using key candidates from libnative-lib.so."""
import os, sys, io, hashlib, struct
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from Crypto.Cipher import AES

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
PAYLOAD = os.path.join(OUT, "unpacked", "VIDEO20012026mp4_2", "assets", "cvsmcn.json")

with open(PAYLOAD, "rb") as f:
    ct = f.read()
print(f"[+] payload size: {len(ct):,}  first16: {ct[:16].hex()}  last16: {ct[-16:].hex()}")

# Key candidate from native lib strings
RAW_KEY = b"sqsmlH2NOLPXeaDIFGnMEOdG6Uc2mV"   # 30 bytes
KEY_CANDIDATES = [
    ("raw30", RAW_KEY),
    ("first16", RAW_KEY[:16]),
    ("first24", RAW_KEY[:24]),
    ("first32_pad0", RAW_KEY + b"\x00" * 2),
    ("md5",    hashlib.md5(RAW_KEY).digest()),                # 16 bytes
    ("sha1_16",hashlib.sha1(RAW_KEY).digest()[:16]),
    ("sha256", hashlib.sha256(RAW_KEY).digest()),             # 32 bytes
    ("sha256_16", hashlib.sha256(RAW_KEY).digest()[:16]),
    ("sha512_32", hashlib.sha512(RAW_KEY).digest()[:32]),
    ("utf16le", RAW_KEY.decode().encode("utf-16-le")[:32]),
    ("utf16le_16", RAW_KEY.decode().encode("utf-16-le")[:16]),
]

IV_CANDIDATES = [
    ("zeros",         b"\x00" * 16),
    ("first16_ct",    ct[:16]),                 # IV = first block of ciphertext
    ("key_first16",   RAW_KEY[:16]),
    ("md5_iv",        hashlib.md5(RAW_KEY).digest()),
    ("first16_key_md5", hashlib.md5(RAW_KEY[:16]).digest()),
    ("ydbllnjd",      b"ydbllnjd.com\x00\x00\x00\x00"),
    ("KeyManager",    b"KeyManagerKeyMan"),
]

def looks_dex(b): return b[:8].startswith(b"dex\n0")
def looks_zip(b): return b[:4] == b"PK\x03\x04"
def looks_apk(b): return b[:4] == b"PK\x03\x04"
def looks_text(b):
    if not b: return False
    sample = b[:512]
    printable = sum(1 for x in sample if 32 <= x < 127 or x in (9,10,13))
    return printable / len(sample) > 0.85
def quality(b):
    score = 0
    if looks_dex(b): score += 100
    if looks_zip(b): score += 100
    if looks_text(b): score += 50
    # english-ish
    if any(w in b[:4096] for w in (b"http", b"://", b"http://", b"https://", b"telegram", b"bot", b".com", b".uz", b".tg", b"android", b"package", b"class", b"public", b"private", b"function")):
        score += 200
    # entropy is moderate (compressed text)
    return score

best = []
for kname, k in KEY_CANDIDATES:
    if len(k) not in (16, 24, 32):
        continue
    # ECB
    try:
        c = AES.new(k, AES.MODE_ECB)
        pt = c.decrypt(ct[:max(16, len(ct) - (len(ct) % 16))])
        s = quality(pt)
        if s >= 50:
            best.append((s, f"AES-ECB key={kname}", pt[:200].hex(), pt[:200]))
    except Exception as e: pass
    # CBC
    for ivname, iv in IV_CANDIDATES:
        if len(iv) != 16: continue
        try:
            c = AES.new(k, AES.MODE_CBC, iv)
            pt = c.decrypt(ct[:max(16, len(ct) - (len(ct) % 16))])
            s = quality(pt)
            if s >= 50:
                best.append((s, f"AES-CBC key={kname} iv={ivname}", pt[:200].hex(), pt[:200]))
        except Exception as e: pass
    # CBC with IV = first 16 bytes of ciphertext, rest is payload (most common pattern)
    try:
        iv = ct[:16]
        body = ct[16:16 + ((len(ct) - 16) // 16) * 16]
        c = AES.new(k, AES.MODE_CBC, iv)
        pt = c.decrypt(body)
        s = quality(pt)
        if s >= 50:
            best.append((s, f"AES-CBC key={kname} iv=ct[:16] body=ct[16:]", pt[:200].hex(), pt[:200]))
    except Exception as e: pass
    # CTR (use first 16 bytes as nonce, rest as ciphertext)
    try:
        from Crypto.Util import Counter
        nonce = ct[:16]
        body = ct[16:]
        # CTR with 16-byte nonce: use first 8 as nonce, counter initial = bytes 8..16 → int
        ctr = Counter.new(128, initial_value=int.from_bytes(nonce, "big"))
        c = AES.new(k, AES.MODE_CTR, counter=ctr)
        pt = c.decrypt(body)
        s = quality(pt)
        if s >= 50:
            best.append((s, f"AES-CTR key={kname} nonce=ct[:16]", pt[:200].hex(), pt[:200]))
    except Exception as e: pass

best.sort(key=lambda x: -x[0])
print(f"\n[+] best hits ({len(best)}):")
for score, label, hex_, raw in best[:20]:
    print(f"  score={score}  {label}")
    print(f"    pt_head: {raw[:160]!r}")

if best:
    s, label, hex_, raw = best[0]
    # save full decrypt
    parts = label.split(" ")
    print(f"\n[+] Best: {label}")
    # try to save with this exact config
    # Reproduce
    kname = [x for x in parts if x.startswith("key=")][0].split("=",1)[1]
    key_map = dict(KEY_CANDIDATES)
    k = key_map[kname]
    out_path = os.path.join(OUT, "VIDEO_cvsmcn_decrypted.bin")
    if "ECB" in label:
        full = AES.new(k, AES.MODE_ECB).decrypt(ct[:len(ct) - (len(ct)%16)])
    elif "iv=ct[:16]" in label:
        full = AES.new(k, AES.MODE_CBC, ct[:16]).decrypt(ct[16:16 + ((len(ct)-16)//16)*16])
    elif "CTR" in label:
        from Crypto.Util import Counter
        nonce = ct[:16]; body = ct[16:]
        ctr = Counter.new(128, initial_value=int.from_bytes(nonce, "big"))
        full = AES.new(k, AES.MODE_CTR, counter=ctr).decrypt(body)
    else:
        # CBC with named IV
        ivname = [x for x in parts if x.startswith("iv=")][0].split("=",1)[1]
        iv_map = dict(IV_CANDIDATES)
        iv = iv_map[ivname]
        full = AES.new(k, AES.MODE_CBC, iv).decrypt(ct[:len(ct) - (len(ct)%16)])
    with open(out_path, "wb") as f:
        f.write(full)
    print(f"    saved -> {out_path}  size={len(full):,}")
else:
    print("[!] No promising hits with these key/iv candidates.")
