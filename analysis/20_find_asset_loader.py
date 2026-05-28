"""Stage 20: Find the asset-loader / second-stage-decrypt method in banker DEX.
Look for methods that:
- Reference asset filenames (.spe, .sps, .data, .bak)
- Call AssetManager.open() or InputStream.read()
- Also call XOR / decrypt routines
"""
import os, sys, io, json, re, logging
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from loguru import logger
logger.remove()
logging.disable(logging.CRITICAL)
from androguard.misc import AnalyzeAPK

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
UNPACK = os.path.join(OUT, "unpacked")

# For each banker, the asset names we want to track
TARGETS = {
    "RASMLAR_18_fixed.apk":          ["zejcc.spe", "02cce475f26041e8.data", "ahihasm.sps", "titmtilhmof.spe"],
    "RASMLAR_8_fixed.apk":           ["cqivytyakr.spe", "d55abfda1195fb77.bak", "esqvay.sps", "daafypkct.spe"],
    "toydanfotolar9jpg_10_fixed.apk":["oiprcd.sps", "5c8d3cf99f5cf4ac.dat", "wefa.sps", "njwi.spe"],
}
# VID_23856 — unicode name
for f in os.listdir(UNPACK):
    if f.startswith("VID_23856") and f.endswith("_fixed.apk"):
        TARGETS[f] = ["nhondpu.sps", "npxtowvrif.spe", "skxnubh.sps"]

for ap, names in TARGETS.items():
    p = os.path.join(UNPACK, ap)
    if not os.path.isfile(p): continue
    print(f"\n{'='*72}\n[+] {ap}\n{'='*72}")
    a, d, dx = AnalyzeAPK(p)

    # Use the decrypt method to deobfuscate strings on the fly
    # We'll just look at raw method bodies and resolve const-string refs to the decrypt method
    # First, find ALL methods referencing AssetManager or InputStream.read
    asset_methods = []
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
            sig = m.full_name
            # Look for AssetManager / InputStream
            wants = ("getAssets" in body or "AssetManager" in body or
                     "FileInputStream" in body or "InputStream;->read" in body or
                     "ByteArrayOutputStream" in body)
            if wants and "xor-int" in body:
                lits = re.findall(r'const-string [vp]\d+, "([^"]*)"', body)
                asset_methods.append((str(sig), lits, body))
        except: pass

    print(f"  asset-IO + xor methods: {len(asset_methods)}")
    for sig, lits, body in asset_methods[:4]:
        print(f"\n  ----- {sig} -----")
        print(f"  string literals: {lits}")
        for line in body.splitlines()[:120]:
            print(f"    {line}")

    # Also look for methods with DexClassLoader / loadClass
    dex_loaders = []
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
            if "DexClassLoader" in body or "PathClassLoader" in body or "InMemoryDexClassLoader" in body:
                lits = re.findall(r'const-string [vp]\d+, "([^"]*)"', body)
                dex_loaders.append((str(m.full_name), lits, body))
        except: pass
    print(f"\n  DexClassLoader users: {len(dex_loaders)}")
    for sig, lits, body in dex_loaders[:3]:
        print(f"\n  ----- {sig} -----")
        print(f"  string literals: {lits}")
        for line in body.splitlines()[:80]:
            print(f"    {line}")
