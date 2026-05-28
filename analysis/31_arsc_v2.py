"""Stage 31: Use ARSCParser._analyse() and dump string pools directly."""
import os, sys, io, json, struct, logging
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from loguru import logger
logger.remove()
logging.disable(logging.CRITICAL)
from androguard.core.axml import ARSCParser, StringBlock

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
UNPACK = os.path.join(OUT, "unpacked")

def dump_strings_block(parser):
    sb = parser.stringpool_main
    count = sb.stringCount
    print(f"  string pool: {count} strings")
    for i in range(count):
        try:
            s = sb.getString(i)
        except: continue
        if s and len(s) >= 3:
            yield i, s

for d in sorted(os.listdir(UNPACK)):
    p = os.path.join(UNPACK, d)
    if not os.path.isdir(p): continue
    arsc_path = os.path.join(p, "resources.arsc")
    if not os.path.isfile(arsc_path): continue
    print(f"\n{'#'*78}\n# {d}\n{'#'*78}")
    with open(arsc_path, "rb") as f:
        data = f.read()
    parser = ARSCParser(data)
    print(f"  pkgs: {parser.get_packages_names()}")
    for i, s in dump_strings_block(parser):
        # show all
        print(f"    [{i:4}] {s!r}")
