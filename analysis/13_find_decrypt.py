"""Stage 13: Deep dive: dump native lib bytes around encKey/getSecretKey, find decrypt routine in DEX bytecode."""
import os, sys, io, re, logging, json
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from loguru import logger
logger.remove()
logging.disable(logging.CRITICAL)
from androguard.misc import AnalyzeAPK

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
UNPACK = os.path.join(OUT, "unpacked")

# ============================================================
# Part 1: dump all strings from libnative-lib.so and look for
# actual key blob (16/24/32 bytes), readable identifiers near 'encKey'
# ============================================================
SO_PATH = os.path.join(UNPACK, "VIDEO20012026mp4_2", "assets", "libs", "arm64-v8a", "libnative-lib.so")
with open(SO_PATH, "rb") as f:
    sodata = f.read()
print(f"[+] {SO_PATH}  size={len(sodata):,}")

# Find 'encKey' and 'getSecretKey' and dump context bytes
for marker in (b"encKey", b"getSecretKey", b"_ZL6encKey", b"KeyManager", b"AES"):
    idx = 0
    while True:
        j = sodata.find(marker, idx)
        if j < 0: break
        # Print 64 bytes BEFORE and 96 AFTER
        a = max(0, j - 64); b = min(len(sodata), j + len(marker) + 96)
        print(f"\n  marker={marker!r} @ {j:#x}")
        print(f"    bytes [{a:#x}:{b:#x}]:")
        print(f"      hex:   {sodata[a:b].hex()}")
        printable = "".join(chr(c) if 32 <= c < 127 else "." for c in sodata[a:b])
        print(f"      ascii: {printable}")
        idx = j + 1

# Search the .so for likely key blobs:
# - high-entropy ASCII chunk of length 16, 24, or 32
# - hex string of length 32 or 64
# - bytes after symbol "_ZL6encKey" / "encKey" (likely the actual key data)
print("\n[+] Hex blob candidates (32 hex digits = 16-byte AES key):")
for m in re.finditer(rb"[0-9a-fA-F]{32,64}", sodata):
    print(f"    @ {m.start():#x}: {m.group().decode()}")

print("\n[+] All Base64-looking strings of length 22/24/32/44/64:")
for m in re.finditer(rb"[A-Za-z0-9+/]{22,64}={0,2}", sodata):
    s = m.group().decode("ascii", "ignore")
    if len(s) in (22, 24, 32, 43, 44, 64) and not re.fullmatch(r"[0-9a-fA-F]+", s):
        print(f"    len={len(s)} @ {m.start():#x}: {s}")

# ============================================================
# Part 2: locate decrypt routine in classes.dex of VIDEO.mp4
# ============================================================
print("\n" + "="*72)
print("[+] DEX bytecode hunt — find methods that call Cipher.doFinal and Base64.decode")
print("="*72)

apk = os.path.join(UNPACK, "VIDEO20012026mp4_2_fixed.apk")
a, d, dx = AnalyzeAPK(apk)

# Find class methods which use Cipher in their bytecode
cipher_callers = []
for m in dx.get_methods():
    try:
        if m.is_external(): continue
        method = m.get_method()
        code = method.get_code() if method else None
        if not code: continue
        ins_text = []
        for ins in method.get_instructions():
            try:
                ins_text.append(ins.get_name() + " " + ins.get_output())
            except Exception:
                pass
        full = "\n".join(ins_text)
        if "javax/crypto/Cipher" in full and ("doFinal" in full or "getInstance" in full):
            cipher_callers.append((str(m.full_name), full))
    except Exception:
        pass

print(f"  found {len(cipher_callers)} methods using javax/crypto/Cipher")
for name, body in cipher_callers[:10]:
    print(f"\n  ----- {name} -----")
    # print first 60 lines
    for line in body.splitlines()[:80]:
        print(f"    {line}")
