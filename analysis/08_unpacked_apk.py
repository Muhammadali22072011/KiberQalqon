"""Stage 8: Re-run androguard on FIXED APKs to get real classes, strings, intent filters."""
import os, sys, io, json, logging, re
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from loguru import logger
logger.remove()
logging.disable(logging.CRITICAL)
from androguard.misc import AnalyzeAPK

OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"
UNPACK = os.path.join(OUT, "unpacked")

# Hunt these (regex against full method names and against strings)
HUNTS = {
    "SMS_API":          [r";->sendTextMessage", r";->sendMultipartTextMessage", r"Landroid/telephony/SmsManager", r"Landroid/provider/Telephony"],
    "SMS_RECEIVER":     [r"android\.provider\.Telephony\.SMS_RECEIVED", r"SMS_DELIVER"],
    "CALL_API":         [r";->getCallState", r"Landroid/telephony/TelephonyManager", r"PHONE_STATE"],
    "CONTACTS":         [r"ContactsContract", r"content://contacts", r"content://com\.android\.contacts"],
    "CALL_LOG":         [r"CallLog", r"content://call_log"],
    "ACCESSIBILITY":    [r"Landroid/accessibilityservice/AccessibilityService", r"AccessibilityNodeInfo", r"AccessibilityEvent", r"performGlobalAction", r"dispatchGesture"],
    "OVERLAY":          [r"TYPE_APPLICATION_OVERLAY", r"TYPE_SYSTEM_ALERT", r"WindowManager.*?addView", r"SYSTEM_ALERT_WINDOW"],
    "DEVICE_ADMIN":     [r"DevicePolicyManager", r"DeviceAdminReceiver", r";->lockNow", r";->resetPassword"],
    "DEX_LOAD":         [r"DexClassLoader", r"PathClassLoader", r"InMemoryDexClassLoader", r";->loadClass", r";->defineClass"],
    "REFLECTION":       [r"Ljava/lang/reflect/Method;->invoke", r"Ljava/lang/Class;->forName"],
    "EXEC":             [r"Ljava/lang/Runtime;->exec", r"Ljava/lang/ProcessBuilder"],
    "INSTALL_APK":      [r"PackageInstaller", r"application/vnd\.android\.package-archive", r"REQUEST_INSTALL_PACKAGES"],
    "NFC_HCE":          [r"HostApduService", r"HostNfcFService", r"NfcAdapter"],
    "NFC_TAG":          [r"Landroid/nfc/Tag;", r"IsoDep", r"NfcA", r"NfcB", r"transceive"],
    "BIOMETRIC":        [r"BiometricPrompt", r"FingerprintManager"],
    "CLIPBOARD":        [r"ClipboardManager;->", r"getPrimaryClip"],
    "LOCATION":         [r"LocationManager", r"FusedLocationProvider"],
    "AUDIO_REC":        [r"MediaRecorder", r"AudioRecord"],
    "CAMERA":           [r"Landroid/hardware/Camera", r"camera2/CameraManager"],
    "SCREEN_CAP":       [r"MediaProjection", r"VirtualDisplay"],
    "TELEGRAM":         [r"api\.telegram\.org", r"telegram\.bot"],
    "AES":              [r"AES/CBC", r"AES/GCM", r"AES/ECB", r"javax/crypto/Cipher"],
    "BASE64":           [r"Landroid/util/Base64", r"Ljava/util/Base64"],
    "SOCKET":           [r"Ljava/net/Socket", r"WebSocket"],
    "HIDE_ICON":        [r"setComponentEnabledSetting", r"COMPONENT_ENABLED_STATE_DISABLED"],
    "STOP_AV":          [r"killBackgroundProcesses", r"force-stop"],
    "BANKING_UZ":       [r"uzcard", r"humo", r"click\.uz", r"payme", r"uztele", r"asaka", r"ipak", r"kapital", r"qishloq", r"agrobank", r"infinbank", r"xalq", r"anor", r"tbc", r"alif", r"hamkor"],
    "JS_INJECT":        [r"WebView;->loadUrl\(Ljava/lang/String;\)", r"javascript:", r"WebView;->addJavascriptInterface", r"evaluateJavascript"],
    "ROOT_CHECK":       [r"/system/bin/su", r"/system/xbin/su", r"isRoot", r"RootBeer"],
}

apks = [
    "RASMLAR_18_fixed.apk",
    "RASMLAR_8_fixed.apk",
    "VIDEO20012026mp4_2_fixed.apk",
    "toydanfotolar9jpg_10_fixed.apk",
]
# VID_23856 has unicode filename — find it
for f in os.listdir(UNPACK):
    if f.startswith("VID_23856") and f.endswith("_fixed.apk"):
        apks.append(f)

results = {}
for ap in apks:
    p = os.path.join(UNPACK, ap)
    if not os.path.isfile(p):
        print(f"  MISSING: {ap}")
        continue
    print(f"\n{'='*72}\n[+] {ap}\n{'='*72}")
    a, d, dx = AnalyzeAPK(p)

    # Class summary
    all_classes = sorted({c.name for c in dx.get_classes()})
    user = [c for c in all_classes if not c.startswith(("Landroid/","Ljava/","Lkotlin","Landroidx/","Lcom/google/","Lokhttp","Lretrofit","Lokio","Lkotlinx/","Lcom/squareup/","Lcom/airbnb/","Lcom/bumptech/"))]
    print(f"  total classes={len(all_classes)}  user={len(user)}")
    print(f"  package={a.get_package()}  main={a.get_main_activity()}")
    print(f"  services={a.get_services()}  receivers={a.get_receivers()}")

    # build haystacks
    method_haystack = "\n".join(str(m.full_name) for m in dx.get_methods())
    strings = []
    for sv in dx.get_strings():
        try: strings.append(str(sv.get_value()))
        except: pass

    hits = {}
    for cat, patterns in HUNTS.items():
        m_hits = []
        s_hits = []
        for pat in patterns:
            try:
                if re.search(pat, method_haystack, re.IGNORECASE):
                    m_hits.append(pat)
            except re.error: pass
        for st in strings[:30000]:
            for pat in patterns:
                try:
                    if re.search(pat, st, re.IGNORECASE):
                        s_hits.append(f"{pat} -> {st[:160]}")
                        break
                except re.error: pass
        if m_hits or s_hits:
            hits[cat] = {"in_methods": list(set(m_hits)), "in_strings": list(dict.fromkeys(s_hits))[:10]}

    # Direct extracts
    long_urls   = sorted({m for st in strings for m in re.findall(r"https?://[A-Za-z0-9._\-/%?=&:#~+]{4,200}", st)})
    long_tg_bot = sorted({m for st in strings for m in re.findall(r"\b\d{6,12}:[A-Za-z0-9_-]{30,50}\b", st)})
    suspect_dom = sorted({m for st in strings for m in re.findall(r"\b(?=[A-Za-z0-9-]{1,63}\.)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+(?:com|net|org|info|biz|ru|uz|xyz|top|club|site|online|store|shop|cc|me|tg|in|app|cyou|live|space|website|tk|ml|cf|ga|pw|bot|cn|io)\b", st)})

    print("  --- HITS ---")
    for cat, h in hits.items():
        n = len(h["in_methods"]) + len(h["in_strings"])
        print(f"    [{cat}] {n} hits — methods:{h['in_methods'][:3]}")
        for s in h["in_strings"][:2]:
            print(f"        S: {s[:200]}")

    print(f"  URLs found: {long_urls[:15]}")
    print(f"  TG bots: {long_tg_bot[:10]}")
    print(f"  suspect domains: {[d for d in suspect_dom if not any(x in d for x in ['google','jetbrains','apache','adobe','android.app','android.net','Rect.','schemas'])][:25]}")
    # user-defined class names
    print(f"  user classes (first 60):")
    for c in user[:60]:
        print(f"     {c}")

    results[ap] = {
        "package": a.get_package(),
        "main": a.get_main_activity(),
        "services": a.get_services(),
        "receivers": a.get_receivers(),
        "permissions": sorted(a.get_permissions()),
        "classes_total": len(all_classes),
        "classes_user": len(user),
        "user_classes": user,
        "urls": long_urls,
        "telegram_bots": long_tg_bot,
        "suspect_domains": sorted({d for d in suspect_dom if not any(x in d for x in ['google','jetbrains','apache','adobe','android.app','android.net','Rect.','schemas'])}),
        "hits": hits,
    }

with open(os.path.join(OUT, "08_unpacked_apk.json"), "w", encoding="utf-8") as f:
    json.dump(results, f, ensure_ascii=False, indent=2)
print(f"\n[+] Saved -> {OUT}\\08_unpacked_apk.json")
