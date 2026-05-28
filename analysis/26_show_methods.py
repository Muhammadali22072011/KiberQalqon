"""Stage 26: Dump SPECIFIC critical methods fully with all literals decrypted inline."""
import os, sys, io, re, json, base64, logging
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from loguru import logger
logger.remove()
logging.disable(logging.CRITICAL)
from androguard.misc import AnalyzeAPK

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
UNPACK = os.path.join(OUT, "unpacked")

KEYS = {
    "RASMLAR_18_fixed.apk":          {"base": b"JYTAs0m31lxvwkQE42Y10Ktm",        "top": b"3183701586F97GhYNSURErMMwPeAS33H"},
    "RASMLAR_8_fixed.apk":           {"base": b"UmmYqVRrK46tVxAG5PDVUI5rSF72a6p1", "top": b"551712693drkGqsgO05tb31vbvZGkA0P"},
    "toydanfotolar9jpg_10_fixed.apk":{"base": b"AVbmxP9CNlQZRrzvnJhFw92n",         "top": b"347692886QFqc74H0w07DWz51sMHjqB4"},
}
for f in os.listdir(UNPACK):
    if f.startswith("VID_23856") and f.endswith("_fixed.apk"):
        KEYS[f] = {"base": b"C49yA8hXYvs5cz7wGeEX7cQLTzYn", "top": b"358200732kyGoz80aYtUropyXbvrXv9h"}

def dec(s, key):
    try:
        pad = (-len(s)) % 4
        raw = base64.b64decode(s + "="*pad, validate=False)
    except: return None
    pt = bytes(b ^ key[i%len(key)] for i, b in enumerate(raw))
    try:
        d = pt.decode("utf-8")
        printable = sum(1 for c in d if 32<=ord(c)<127 or c in "\n\r\t") / max(1, len(d))
        if printable > 0.85: return d
    except: pass
    return None

def annotate_line(line, keys):
    """Replace const-string ... "X" with decrypted text inline."""
    m = re.search(r'const-string [vp]\d+, "([^"]*)"', line)
    if not m: return line
    s = m.group(1)
    if not re.match(r"^[A-Za-z0-9+/]{4,400}={0,2}$", s): return line
    for kn, k in keys.items():
        d = dec(s, k)
        if d:
            return f"{line}   ;; ({kn}->) {d!r}"
    return line

# Methods to dump (per APK)
TARGETS = {
    "RASMLAR_18_fixed.apk": [
        "Dodkzdklftszwqtqxwdikwxkgvzuudrqfr",   # byte XOR
        "Cwirfqyahbjtyclklnanwcl",              # run / installs APK
        "onCreate",                              # anti-frida
        "Czbuzjwfbqdnznzwboqaha",                # File deletion?
        "Gjiidqnxyecptzulvortrhfdjgjgsxgmxs",   # asset loader + extra targets
    ],
    "RASMLAR_8_fixed.apk": [
        "Cwirfqyahbjtyclklnanwcl",
        "onCreate",
        "Dodkzdkl",                             # may have different name
        "ejpwqplfj",                            # RASMLAR_8 base decrypt
    ],
    "toydanfotolar9jpg_10_fixed.apk": [
        "Cwirfqyahbjtyclklnanwcl",
        "onCreate",
        "trtobhtalyz",                          # base decrypt
    ],
}
for f in os.listdir(UNPACK):
    if f.startswith("VID_23856") and f.endswith("_fixed.apk"):
        TARGETS[f] = ["uzrkycnrf", "Cwirfqyahbjtyclklnanwcl", "onCreate"]

for apk_name, target_names in TARGETS.items():
    apk = os.path.join(UNPACK, apk_name)
    if not os.path.isfile(apk): continue
    print(f"\n{'#'*78}\n# {apk_name}\n{'#'*78}")
    keys = KEYS[apk_name]
    a, d, dx = AnalyzeAPK(apk)

    for m in dx.get_methods():
        try:
            if m.is_external(): continue
            name = str(m.full_name)
            if not any(t in name for t in target_names): continue
            method = m.get_method()
            if not method or not method.get_code(): continue
            lines = []
            for ins in method.get_instructions():
                try:
                    lines.append(ins.get_name() + " " + ins.get_output())
                except: pass
            body = "\n".join(lines)
            print(f"\n  -------- {name} --------")
            for line in body.splitlines():
                print(f"    {annotate_line(line, keys)}")
        except Exception as e:
            pass
