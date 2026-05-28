"""Stage 2: DEX analysis — classes, API calls, hooks, sensitive method usage."""
import os, sys, json, io, logging, re
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from loguru import logger
logger.remove()
logging.disable(logging.CRITICAL)
from androguard.misc import AnalyzeAPK

SAMPLES_DIR = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\новые вирусы"
OUT = r"C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis"

# Sensitive API patterns (class;method) - what to hunt
SENSITIVE = {
    "SMS_READ":            [r"Landroid/provider/Telephony\$Sms;", r"Landroid/telephony/SmsMessage;", r"content://sms"],
    "SMS_SEND":            [r"Landroid/telephony/SmsManager;->sendTextMessage", r"sendMultipartTextMessage"],
    "CONTACTS":            [r"Landroid/provider/ContactsContract", r"content://contacts", r"content://com\.android\.contacts"],
    "CALL_LOG":            [r"Landroid/provider/CallLog", r"content://call_log"],
    "ACCESSIBILITY":       [r"Landroid/accessibilityservice/AccessibilityService", r"AccessibilityNodeInfo", r"AccessibilityEvent"],
    "OVERLAY":             [r"TYPE_APPLICATION_OVERLAY", r"TYPE_SYSTEM_ALERT", r"TYPE_SYSTEM_OVERLAY", r"WindowManager.*addView"],
    "DEVICE_ADMIN":        [r"DevicePolicyManager", r"DeviceAdminReceiver", r"lockNow", r"resetPassword"],
    "DYNAMIC_LOAD":        [r"Ldalvik/system/DexClassLoader", r"Ldalvik/system/PathClassLoader", r"Ldalvik/system/InMemoryDexClassLoader"],
    "REFLECTION":          [r"Ljava/lang/reflect/Method;->invoke", r"Ljava/lang/Class;->forName"],
    "EXEC":                [r"Ljava/lang/Runtime;->exec", r"Ljava/lang/ProcessBuilder"],
    "INSTALL_APK":         [r"PackageInstaller", r"REQUEST_INSTALL_PACKAGES", r"application/vnd\.android\.package-archive"],
    "NFC_HCE":             [r"Landroid/nfc/cardemulation/HostApduService", r"HostNfcFService", r"NfcAdapter"],
    "NFC_RELAY":           [r"Landroid/nfc/Tag;", r"IsoDep", r"transceive"],
    "BIOMETRIC":           [r"BiometricPrompt", r"FingerprintManager"],
    "CLIPBOARD":           [r"Landroid/content/ClipboardManager;->", r"getPrimaryClip"],
    "LOCATION":            [r"Landroid/location/LocationManager", r"FusedLocationProvider"],
    "CAMERA":              [r"Landroid/hardware/Camera;", r"Landroid/hardware/camera2/CameraManager;"],
    "AUDIO_RECORD":        [r"Landroid/media/MediaRecorder;", r"AudioRecord"],
    "ROOT_CHECK":          [r"/system/bin/su", r"/system/xbin/su", r"isRoot", r"RootBeer"],
    "EMULATOR_CHECK":      [r"isEmulator", r"goldfish", r"ro\.kernel\.qemu"],
    "HTTP_LIB":            [r"Lokhttp3/", r"Lretrofit2/", r"HttpURLConnection", r"Lorg/apache/http"],
    "WEBSOCKET":           [r"Lokhttp3/WebSocket", r"Ljava/net/Socket"],
    "TELEGRAM_API":        [r"api\.telegram\.org", r"bot[0-9]+:[A-Za-z0-9_-]{30,}"],
    "ENCRYPTION":          [r"Ljavax/crypto/Cipher;->getInstance", r"AES/CBC", r"AES/GCM", r"RC4", r"DES"],
    "BASE64":              [r"Landroid/util/Base64;->decode", r"Ljava/util/Base64"],
    "ACCESSIBILITY_GESTURE": [r"performGlobalAction", r"dispatchGesture", r"GLOBAL_ACTION_BACK", r"GLOBAL_ACTION_HOME"],
    "SCREEN_CAPTURE":      [r"MediaProjection", r"VirtualDisplay"],
    "UNINSTALL":           [r"DELETE_PACKAGE", r"REQUEST_DELETE_PACKAGES", r"ACTION_UNINSTALL_PACKAGE"],
    "HIDE_ICON":           [r"PackageManager;->setComponentEnabledSetting"],
}

samples = sorted([f for f in os.listdir(SAMPLES_DIR) if f.lower().endswith(".apk")])

results = {}
for s in samples:
    p = os.path.join(SAMPLES_DIR, s)
    print(f"\n{'='*72}\n[+] {s}\n{'='*72}")
    a, d, dx = AnalyzeAPK(p)

    # All class names (count + first 30)
    all_classes = sorted({c.name for c in dx.get_classes()})
    user_classes = [c for c in all_classes if not c.startswith(("Landroid/", "Ljava/", "Lkotlin", "Landroidx/", "Lcom/google/", "Lokhttp", "Lretrofit", "Lokio", "Lkotlinx/"))]
    print(f"  classes total={len(all_classes)}  user-defined={len(user_classes)}")

    # Build a single haystack: all method-call descriptors + all strings
    method_descs = []
    for m in dx.get_methods():
        try:
            method_descs.append(str(m.full_name))
        except Exception:
            pass
    # Strings
    strings = []
    for sv in dx.get_strings():
        try:
            strings.append(str(sv.get_value()))
        except Exception:
            pass

    # API hits
    hay_methods = "\n".join(method_descs)
    hits = {}
    for cat, pats in SENSITIVE.items():
        m = []
        for pat in pats:
            try:
                if re.search(pat, hay_methods):
                    m.append(pat + " (in methods)")
            except re.error:
                pass
        for st in strings:
            for pat in pats:
                try:
                    if re.search(pat, st):
                        m.append(pat + f" (string: {st[:120]})")
                        break
                except re.error:
                    pass
        if m:
            hits[cat] = list(dict.fromkeys(m))[:8]

    # Print summary
    for cat, m in hits.items():
        print(f"  [{cat}]  {len(m)} hits")
        for mm in m[:3]:
            print(f"      {mm[:160]}")

    results[s] = {
        "classes_total": len(all_classes),
        "classes_user": len(user_classes),
        "user_class_sample": user_classes[:50],
        "hits": hits,
        "strings_count": len(strings),
        "methods_count": len(method_descs),
    }

with open(os.path.join(OUT, "02_dex.json"), "w", encoding="utf-8") as f:
    json.dump(results, f, ensure_ascii=False, indent=2)
print(f"\n[+] Saved -> {OUT}\\02_dex.json")
