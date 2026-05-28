"""Stage 29: Dump ALL strings from resources.arsc of every APK — these may be the real secrets!"""
import os, sys, io, logging, re, json
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from loguru import logger
logger.remove()
logging.disable(logging.CRITICAL)
from androguard.misc import AnalyzeAPK

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
UNPACK = os.path.join(OUT, "unpacked")

# Resource IDs found in VIDEO.mp4 decrypt method
VIDEO_RES_IDS = [0x7f090010, 0x7f090003, 0x7f090011, 0x7f090012, 0x7f090004,
                 2131296272, 2131296259, 2131296273, 2131296274, 2131296260]

apks = [f for f in os.listdir(UNPACK) if f.endswith("_fixed.apk")]

for apk_name in apks:
    apk = os.path.join(UNPACK, apk_name)
    print(f"\n{'#'*78}\n# {apk_name}\n{'#'*78}")
    a, d, dx = AnalyzeAPK(apk)
    arscs = a.get_android_resources()
    if not arscs:
        print("  no arsc")
        continue
    package = a.get_package()
    print(f"  package: {package}")
    # dump all resources types and all strings
    try:
        for pkg in arscs.get_packages_names():
            print(f"\n  --- package: {pkg} ---")
            try:
                resmap = arscs.get_res_id_by_key(pkg, "string", "app_name")
            except: pass
            # iterate all types
            try:
                types = arscs.get_res_configs(arscs.get_res_id_by_key(pkg, "string", "app_name") or 0)
                pass
            except: pass
            # Easier: iterate via get_strings_resources()
            try:
                all_strings = arscs.get_strings_resources()
                print(f"  total string resources: ?")
            except: pass

        # Direct approach: load resources.arsc via androguard and dump every string
        from androguard.core.axml import ARSCParser
        with open(os.path.join(UNPACK, apk_name.replace("_fixed.apk",""), "resources.arsc"), "rb") as f:
            arsc_data = f.read()
        arsc = ARSCParser(arsc_data)
        print(f"  ARSC parsed.  packages: {arsc.get_packages_names()}")
        for pkg in arsc.get_packages_names():
            print(f"\n  ----- pkg: {pkg} -----")
            # get all resource types
            res_types = arsc.get_locales(pkg)
            for typ in arsc.get_types(pkg, "\x00\x00"):
                print(f"  type: {typ}")
                ids = arsc.get_res_id_by_key(pkg, typ, "app_name")
            # iterate via resourceset
            for cur in arsc.values[pkg]:
                vals = arsc.values[pkg][cur]
                for typ_name in vals:
                    items = vals[typ_name]
                    print(f"    locale={cur}  type={typ_name}  count={len(items)}")
                    for item in items[:200]:
                        # item is tuple (name, value)
                        if isinstance(item, tuple) and len(item) >= 2:
                            name, value = item[0], item[1]
                            if isinstance(value, str) and len(value) > 3:
                                # skip android stdlib values
                                if not any(x in value for x in ("@7F", "@7f", "<vector", "<set>", "<selector", "<animator", "<resources")):
                                    print(f"      {name} = {value[:200]!r}")
    except Exception as e:
        import traceback
        print(f"  ERR: {e}")
        traceback.print_exc()
