"""Stage 9: Dump native .so strings, AXML manifest text, encrypted-asset headers, embedded-zip preview."""
import os, sys, io, re, logging, json, struct
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from loguru import logger
logger.remove()
logging.disable(logging.CRITICAL)
from androguard.core.axml import AXMLPrinter

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
UNPACK = os.path.join(OUT, "unpacked")

def strings(b, minlen=6):
    return [m.decode("ascii", "ignore") for m in re.findall(rb"[\x20-\x7e]{%d,}" % minlen, b)]

def head_hex(b, n=128):
    return b[:n].hex(" ")

results = {}
for d in sorted(os.listdir(UNPACK)):
    full = os.path.join(UNPACK, d)
    if not os.path.isdir(full): continue
    print(f"\n{'='*72}\n[+] {d}\n{'='*72}")
    out = {}

    # AndroidManifest.xml -> text
    manifest_path = os.path.join(full, "AndroidManifest.xml")
    if os.path.isfile(manifest_path):
        try:
            with open(manifest_path, "rb") as f:
                raw = f.read()
            xml = AXMLPrinter(raw).get_xml().decode("utf-8", "replace")
            out["manifest_xml"] = xml
            print("  --- AndroidManifest.xml (decoded) ---")
            for line in xml.splitlines():
                if line.strip():
                    print(f"    {line}")
        except Exception as e:
            print(f"  manifest decode err: {e}")
            out["manifest_xml_err"] = str(e)

    # Native .so strings
    libs = []
    for root, _, fs in os.walk(os.path.join(full, "assets", "libs")):
        for fn in fs:
            if fn.endswith(".so"):
                libs.append(os.path.join(root, fn))
    if libs:
        # take arm64
        target = [l for l in libs if "arm64" in l] or libs
        lp = target[0]
        print(f"  --- Native lib: {os.path.relpath(lp, full)} ---")
        with open(lp, "rb") as f:
            data = f.read()
        s_all = strings(data, 6)
        # Filter "interesting"
        s_int = [s for s in s_all if any(k in s.lower() for k in ("http","://","key","cipher","aes","decrypt","encrypt","dex","load","class","sym","init","jni","native","payload","libnative","unpack","secret","dump","cvsmcn"))]
        s_int = list(dict.fromkeys(s_int))[:120]
        print(f"  total ascii runs: {len(s_all)}  interesting: {len(s_int)}")
        for s in s_int:
            print(f"    SO: {s[:200]}")
        out["native_lib"] = {"path": os.path.relpath(lp, full), "interesting_strings": s_int}

    # Encrypted asset headers
    enc_assets = []
    assets_dir = os.path.join(full, "assets")
    if os.path.isdir(assets_dir):
        for root, _, fs in os.walk(assets_dir):
            for fn in fs:
                if fn.endswith((".spe",".sps",".data",".dat",".bak",".json")):
                    p = os.path.join(root, fn)
                    sz = os.path.getsize(p)
                    if sz < 200: continue
                    with open(p, "rb") as f:
                        head = f.read(64)
                        f.seek(max(0, sz - 64))
                        tail = f.read(64)
                    rel = os.path.relpath(p, full).replace("\\","/")
                    enc_assets.append({"name": rel, "size": sz, "head_hex": head.hex(), "tail_hex": tail.hex()})
                    print(f"  ENC ASSET {rel}  size={sz}")
                    print(f"    head: {head.hex(' ')}")
                    print(f"    tail: {tail.hex(' ')}")
    out["encrypted_assets"] = enc_assets

    results[d] = out

with open(os.path.join(OUT, "09_native_and_manifest.json"), "w", encoding="utf-8") as f:
    json.dump(results, f, ensure_ascii=False, indent=2)
print(f"\n[+] Saved -> {OUT}\\09_native_and_manifest.json")
