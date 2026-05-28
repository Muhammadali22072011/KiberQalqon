"""Stage 1: Manifest + permissions + components."""
import os, sys, json, traceback, logging, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from loguru import logger
logger.remove()
logging.disable(logging.CRITICAL)
from androguard.misc import AnalyzeAPK

SAMPLES_DIR = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\новые вирусы"
OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"

samples = [f for f in os.listdir(SAMPLES_DIR) if f.lower().endswith(".apk")]

results = {}
for s in samples:
    p = os.path.join(SAMPLES_DIR, s)
    print(f"\n{'='*70}\n[+] {s}\n{'='*70}")
    try:
        a, d, dx = AnalyzeAPK(p)
        info = {
            "file": s,
            "size": os.path.getsize(p),
            "package": a.get_package(),
            "app_name": a.get_app_name(),
            "main_activity": a.get_main_activity(),
            "min_sdk": a.get_min_sdk_version(),
            "target_sdk": a.get_target_sdk_version(),
            "version_name": a.get_androidversion_name(),
            "version_code": a.get_androidversion_code(),
            "permissions": sorted(a.get_permissions()),
            "activities": a.get_activities(),
            "services": a.get_services(),
            "receivers": a.get_receivers(),
            "providers": a.get_providers(),
            "signature_subjects": [c.subject.human_friendly for c in a.get_certificates()],
            "signature_sha1": [c.sha1_fingerprint for c in a.get_certificates()],
        }
        # intent filters
        intents = {}
        for act in info["activities"]:
            f = a.get_intent_filters("activity", act)
            if f: intents[act] = f
        for srv in info["services"]:
            f = a.get_intent_filters("service", srv)
            if f: intents[srv] = f
        for rcv in info["receivers"]:
            f = a.get_intent_filters("receiver", rcv)
            if f: intents[rcv] = f
        info["intent_filters"] = intents
        results[s] = info
        print(f"  pkg={info['package']}  name={info['app_name']}  main={info['main_activity']}")
        print(f"  perms ({len(info['permissions'])}): {info['permissions'][:8]}...")
        print(f"  services: {len(info['services'])}, receivers: {len(info['receivers'])}, activities: {len(info['activities'])}")
    except Exception as e:
        print(f"  ERROR: {e}")
        traceback.print_exc()
        results[s] = {"error": str(e)}

with open(os.path.join(OUT, "01_manifest.json"), "w", encoding="utf-8") as f:
    json.dump(results, f, ensure_ascii=False, indent=2)
print(f"\n[+] Saved -> {OUT}\\01_manifest.json")
