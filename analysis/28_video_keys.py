"""Stage 28: Find VIDEO.mp4 dropper's XOR keys (separate from native AES)."""
import os, sys, io, re, json, base64, logging
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from loguru import logger
logger.remove()
logging.disable(logging.CRITICAL)
from androguard.misc import AnalyzeAPK

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
UNPACK = os.path.join(OUT, "unpacked")

apk = os.path.join(UNPACK, "VIDEO20012026mp4_2_fixed.apk")
a, d, dx = AnalyzeAPK(apk)

print("[+] VIDEO.mp4 DEX — looking for XOR / Base64 decryption methods")

methods_data = []
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
        methods_data.append({"name": str(m.full_name), "body": body})
    except: pass

print(f"  {len(methods_data)} methods\n")

# Find methods that do Base64.decode + xor-int
b64_xor = [m for m in methods_data if "Base64;->decode" in m["body"] and "xor-int" in m["body"]]
print(f"[+] Methods with Base64.decode + xor-int: {len(b64_xor)}")
for m in b64_xor[:10]:
    lits = re.findall(r'const-string [vp]\d+, "([^"]*)"', m["body"])
    lits = [l for l in lits if l]
    print(f"\n  ----- {m['name']} -----")
    print(f"  literals: {lits}")
    for line in m["body"].splitlines()[:80]:
        print(f"    {line}")

# Also: search for getSecretKey callsite
print("\n[+] Methods calling getSecretKey (native AES key fetch)")
for m in methods_data:
    if "getSecretKey" in m["body"]:
        lits = re.findall(r'const-string [vp]\d+, "([^"]*)"', m["body"])
        lits = [l for l in lits if l]
        print(f"\n  ----- {m['name']} -----")
        print(f"  literals: {lits}")
        for line in m["body"].splitlines()[:120]:
            print(f"    {line}")

# Look for Cipher uses
print("\n[+] Methods using javax/crypto/Cipher (AES)")
for m in methods_data:
    if "javax/crypto" in m["body"] or "Cipher" in m["body"]:
        lits = re.findall(r'const-string [vp]\d+, "([^"]*)"', m["body"])
        lits = [l for l in lits if l]
        print(f"\n  ----- {m['name']} -----")
        print(f"  literals: {lits}")
        for line in m["body"].splitlines()[:80]:
            print(f"    {line}")
