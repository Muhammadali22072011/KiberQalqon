"""Stage 30: Properly extract ALL string resource VALUES (not just keys) from resources.arsc."""
import os, sys, io, json, logging
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from loguru import logger
logger.remove()
logging.disable(logging.CRITICAL)
from androguard.core.axml import ARSCParser

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
UNPACK = os.path.join(OUT, "unpacked")

for d in sorted(os.listdir(UNPACK)):
    p = os.path.join(UNPACK, d)
    if not os.path.isdir(p): continue
    arsc_path = os.path.join(p, "resources.arsc")
    if not os.path.isfile(arsc_path): continue
    print(f"\n{'#'*78}\n# {d}\n{'#'*78}")
    with open(arsc_path, "rb") as f:
        data = f.read()
    arsc = ARSCParser(data)
    pkgs = list(arsc.values.keys())
    print(f"  packages in arsc.values: {pkgs}")
    for pkg in pkgs:
        print(f"  package: {pkg}")
        for locale in arsc.values[pkg]:
            try:
                if "string" not in arsc.values[pkg][locale]: continue
                items = arsc.values[pkg][locale]["string"]
                if not items: continue
                print(f"\n  ---- locale='{locale or 'default'}' string resources ({len(items)}) ----")
                for item in items:
                    if isinstance(item, (list, tuple)) and len(item) >= 2:
                        name, value = item[0], item[1]
                        v_str = str(value) if value is not None else ""
                        # show full value
                        print(f"    {name:<60} = {v_str!r}")
            except Exception as e:
                print(f"    ERR locale={locale}: {e}")
