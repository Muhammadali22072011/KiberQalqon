"""Stage 15: Find base XOR key — look at Lizbhposedczokv/ffpmuejysxtpa;->bxwmmwxospk method
and trace back to the base-case decrypt routine.
"""
import os, sys, io, logging, re
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from loguru import logger
logger.remove()
logging.disable(logging.CRITICAL)
from androguard.misc import AnalyzeAPK

UNPACK = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis\unpacked"
apk = os.path.join(UNPACK, "RASMLAR_18_fixed.apk")
a, d, dx = AnalyzeAPK(apk)

# Find every class with a method that does Base64.decode + XOR
print("[+] Looking for decrypt-like methods (Base64.decode + xor loop)\n")

def get_method_body(m):
    method = m.get_method()
    if not method or not method.get_code(): return ""
    lines = []
    for ins in method.get_instructions():
        try:
            lines.append(ins.get_name() + " " + ins.get_output())
        except: pass
    return "\n".join(lines)

results = []
for m in dx.get_methods():
    try:
        if m.is_external(): continue
        body = get_method_body(m)
        if "Base64;->decode" in body and "xor-int" in body:
            # extract any const-string literals in body
            lits = re.findall(r'const-string [vp]\d+, "([^"]*)"', body)
            results.append((str(m.full_name), lits, body))
    except: pass

print(f"[+] Found {len(results)} candidate decrypt methods\n")
for full_name, lits, body in results:
    print(f"\n{'='*72}\n  METHOD: {full_name}\n  STRING LITERALS: {lits}\n{'='*72}")
    for line in body.splitlines():
        print(f"    {line}")
