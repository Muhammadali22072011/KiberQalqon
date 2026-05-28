"""Stage 33: Decrypt cvsmcn.json with the discovered XOR key."""
import os, sys, io, re
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
UNPACK = os.path.join(OUT, "unpacked")

KEY = b"sqsmlH2NOLPXeaDIFGnMEOdG6Uc2mVin"  # 32 bytes from native lib
print(f"[+] key: {KEY!r}  len={len(KEY)}")

def xor(b, k):
    return bytes(x ^ k[i % len(k)] for i, x in enumerate(b))

# Decrypt VIDEO's cvsmcn.json
p = os.path.join(UNPACK, "VIDEO20012026mp4_2", "assets", "cvsmcn.json")
with open(p, "rb") as f:
    ct = f.read()
print(f"[+] {p}  size={len(ct):,}")
print(f"  head: {ct[:32].hex()}")
pt = xor(ct, KEY)
print(f"  decrypted head 32: {pt[:32]}")
print(f"  decrypted head hex: {pt[:32].hex()}")

out_path = os.path.join(OUT, "DECRYPTED_VIDEO_cvsmcn.bin")
with open(out_path, "wb") as f:
    f.write(pt)
print(f"  saved -> {out_path}")

# What is it?
if pt[:4] == b"PK\x03\x04":
    print("  ** IT'S A ZIP/APK! **")
elif pt[:5] == b"dex\n0":
    print("  ** IT'S A DEX! **")
elif pt[:2] == b"\x1f\x8b":
    print("  ** IT'S GZIP **")
elif pt[:1] in b"{[<":
    print("  ** TEXT/JSON/XML **")
else:
    print(f"  ?? first 64: {pt[:64]!r}")

# Now also try with the same key on the encrypted asset files of the bankers — maybe they share the key
print("\n" + "="*70)
print("[+] Testing same key on banker large encrypted assets:")
for sample_dir in sorted(os.listdir(UNPACK)):
    full_dir = os.path.join(UNPACK, sample_dir)
    if not os.path.isdir(full_dir): continue
    assets = os.path.join(full_dir, "assets")
    if not os.path.isdir(assets): continue
    for root, _, files in os.walk(assets):
        for fn in files:
            if not fn.endswith((".spe",".sps",".data",".bak",".dat",".json")): continue
            p = os.path.join(root, fn)
            if os.path.getsize(p) < 100000: continue
            with open(p, "rb") as f:
                ct = f.read()
            pt = xor(ct, KEY)
            head = pt[:32]
            ok = False
            if pt[:4] == b"PK\x03\x04": label = "ZIP/APK"; ok = True
            elif pt[:5] == b"dex\n0": label = "DEX"; ok = True
            elif pt[:2] == b"\x1f\x8b": label = "GZIP"; ok = True
            elif pt[:1] in b"{[<": label = "TEXT"; ok = True
            else:
                sample = pt[:512]
                printable = sum(1 for c in sample if 32<=c<127 or c in (9,10,13))
                if printable/len(sample) > 0.9: label = "TEXT-like"; ok = True
                else: label = None
            short = sample_dir[:25]
            if ok:
                print(f"  ✔ {short}/{fn}  -> {label}")
                outp = os.path.join(OUT, f"DECRYPTED_BIG_{short}_{fn}.bin")
                with open(outp, "wb") as f:
                    f.write(pt)
                print(f"     head: {pt[:64]}")
                print(f"     saved -> {outp}")
            else:
                print(f"  ✘ {short}/{fn}  head: {head[:16].hex()}")
