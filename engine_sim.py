# -*- coding: utf-8 -*-
"""
engine_sim.py — Faithful Python port of KiberQalqon's ApkScanner.scan().

Purpose: run the REAL detection logic (every analyzer + the final verdict
combinator) against APK samples WITHOUT building/installing the Android app.
This lets us measure exactly what the engine catches and what it misses.

Mirrors (1:1 where it matters for the verdict):
  - MaliciousHashes / MaliciousPackages / MaliciousCerts  (blacklists)
  - ZipEncryptionDetector       (GP-flag bit-0 on LFH + CD)
  - ObfuscatedSignatures        (decoded substrings + token hashes)
  - ManifestAnalyzer            (red/orange flags, a11y, device-admin, exported)
  - PermissionCombos            (weighted permission combinations)
  - DexPatternAnalyzer          (classes*.dex byte patterns + packers)
  - DropperDetector             (hidden APK/DEX/ELF/.so + encrypted payload)
  - NativeLibAnalyzer           (suspicious .so imports + entropy)
  - FilenameHeuristic           (8 layers, brand impersonation, homoglyph)
  - looksRandomPackageName      (entropy/consonant/bigram heuristic)
  - Final verdict combinator    (hard signals + score thresholds + sensitivity)

Caveat: IconImpersonationDetector needs on-device reference icons (perceptual
hash of installed Telegram/Click/Payme...). It cannot run offline, so it is
treated as "no match" here. That makes this harness STRICTLY MORE CONSERVATIVE
than the real on-device engine (a real device may catch a few more via icon).
"""
import zipfile
import hashlib
import struct
import re
import os
import sys
import math
import io

# androguard — AXML/cert/package parsing (4.x or 3.x)
_HAVE_AG = True
try:
    from androguard.core.apk import APK
except Exception:
    try:
        from androguard.core.bytecodes.apk import APK
    except Exception:
        _HAVE_AG = False

# Silence androguard's noisy logger (4.x uses loguru, 3.x uses stdlib logging)
try:
    from loguru import logger as _loguru
    _loguru.remove()
except Exception:
    pass
try:
    import logging
    logging.getLogger("androguard").setLevel(logging.CRITICAL)
    for noisy in ("androguard.axml", "androguard.apk", "androguard.core.api_specific_resources"):
        logging.getLogger(noisy).setLevel(logging.CRITICAL)
except Exception:
    pass

P = "android.permission."

# ============================================================
#  Blacklists (mirror of Kotlin objects)
# ============================================================
MALICIOUS_HASHES = {
    "75dd6895575576c0e83706c07c226cb16d23174e2372d8217626c95797e1b215": "Ajina.Banker.lzthzvxte",
    "8d2f128ccae146e5a991e26c45ffc2c0d7cc9dd999338424c911b03e0805599b": "Ajina.Banker.oktgkst",
    "4860aafa2244b0430260d185301f26ac5e3c942f9e60c9fd6b66a322e6d407e9": "Ajina.Banker.yzsfnie",
    "6ca17abd38dc8970aeaff85dc38b97974d3445b3c877f7dc6b1a52045315cc43": "RoundRift.ydbllnjd",
    "5ed26a060cd95e0124be12f7d94afe1f10880e88d6572323e50b571d18f7c174": "Uzbek-dropper.vudgi",
    "de763b5b816deb14a1b9333469c90f3f2743bcacb8c96d0ad1b9397b28e2115b": "Uzbek-dropper.taklifnoma",
    # 2026-05-28 partiyasi (вирусы/ to'liq namunalar)
    "7037735359ef99dbc4a6827fe056738f19b93f16ec732d871df260cd4458e9af": "Ajina.Banker.nzolcwh",
    "74da27210bcd1f068901988c1ff3f8aa9a40dfff1a60a312947e3e47f195d61b": "Ajina.Banker.boyauosdti",
    "b4711ea642a97eac10e41c1fa6316ea4ff484621e2635b3228fe9c212a45c4a5": "Ajina.Banker.clnvwyro",
    "b7c1119c9175c24a6631edce74dcbb181a1e84e3cb59cb8d4fafdf3c2fbef93f": "Ajina.Banker.rsewozxrkn",
    "243b28ff0ac9fd37ce5c6460d844bc8485aeb7e02497b81421384786da4e49dc": "Ajina.Banker.vngoocackr",
    "b77afa48cd87c4ede852fde67ed3858c158db64623cfaecbfdc318bc64ba81a2": "Ajina.Banker.labscleaner",
    "06869e214a8fbc869f88e0f0fbeca315f06389891ac1e3c6ad10152dcaf90f66": "Ajina.Banker.uzyjrglm",
    "97dc105ddd1d5f185d26c0a0eb09be495121936c5d63e1c506a5c89cf0415a06": "Ajina.Banker.rhoeinmfts",
    "76ecc28e5023512879a0c521fc7a9e06d689ab27fd150dca664a6543475d6d92": "Ajina.Banker.gkljgfgha",
    "727c1f787bd1768da41a669d1285bdbf5ddf9c3754eb9fe9262a3973da422489": "Ajina.Banker.osrbizoauk",
}

MALICIOUS_PACKAGES = {
    "com.lzthzvxte.xazoalzxhr": "Ajina.Banker",
    "com.oktgkst.rrcpkge": "Ajina.Banker",
    "com.yzsfnie.sjsztphpis": "Ajina.Banker",
    "com.puhfvysb.nzbftunmqq": "Ajina.Banker",
    "ydbllnjd.com": "RoundRift",
    "pyw.kzxwc": "Uzbek-dropper.vudgi",
    "uzbekchill.com": "Uzbek-dropper.taklifnoma",
    # 2026-05-28 partiyasi
    "com.nzolcwh.jdzkycd": "Ajina.Banker", "com.boyauosdti.ewqejxsd": "Ajina.Banker",
    "com.clnvwyro.jjewziwhq": "Ajina.Banker", "com.rsewozxrkn.fpnsatj": "Ajina.Banker",
    "com.vngoocackr.epstfh": "Ajina.Banker", "com.uzyjrglm.qcvcwxa": "Ajina.Banker",
    "com.rhoeinmfts.ctwmqgb": "Ajina.Banker", "com.gkljgfgha.xitdqvgi": "Ajina.Banker",
    "com.osrbizoauk.hihsrugbo": "Ajina.Banker", "org.labs.cleaner": "Ajina.Banker",
}

MALICIOUS_CERTS = {
    "5db8a5668648061fc388a43df97863a1be3cc9b20764cf5b752b5357a976c307": "Uzbek-dropper.taklifnoma",
    "954ee710c4419d1be570a9874e5460650c014f9897cc454b3da02bc80c8ab42d": "Uzbek-dropper.vudgi",
}

# AppReputation — known-good vendors (mirror of AppReputation.kt). Returns SAFE only
# AFTER hard signals (dropper/icon/blacklist/zip-enc/brand) have already been ruled out.
TRUSTED_PREFIXES = (
    "com.google.", "com.android.", "com.samsung.", "com.sec.", "com.microsoft.",
    "com.facebook.", "com.instagram.", "com.whatsapp", "org.telegram.", "androidx.",
    "com.qualcomm.", "com.mediatek.", "com.qti.", "com.lge.", "com.huawei.",
    "com.hihonor.", "com.miui.", "com.xiaomi.", "com.coloros.", "com.oppo.",
    "com.vivo.", "com.oneplus.", "com.motorola.", "com.sonyericsson.",
    "com.sonymobile.", "com.asus.", "com.transsion.",
)
TRUSTED_EXACT = {
    "ch.protonvpn.android", "com.isaiasmatewos.texpand", "com.viber.voip",
    "com.twitter.android", "com.zhiliaoapp.musically", "com.spotify.music",
    "com.discord", "org.thoughtcrime.securesms", "com.snapchat.android",
    "com.linkedin.android", "com.pinterest", "com.skype.raider", "com.opera.browser",
    "com.opera.mini.native", "org.mozilla.firefox", "com.brave.browser",
    "com.duckduckgo.mobile.android", "com.yandex.browser", "ru.yandex.searchplugin",
    "com.adobe.reader", "com.dropbox.android", "uz.kapitalbank.android", "uz.click.evo",
    "uz.dida.payme", "uz.uzcard.uzcard", "uz.uzum.bank", "uz.tbcbank.mobile",
    "uz.hamkorbank.mobile", "uz.agrobank.mobile", "uz.ipakyulibank.mobile",
    "uz.infinbank.mobile", "uz.davrbank.mobile", "uz.beeline.odp",
    "uz.beeline.selfservice", "uz.mobiuz.android", "uz.ucell.selfcare", "uz.ums.mobile",
    "uz.dunyo.mobile", "uz.soliq.mygov", "uz.yt.dyhcm", "uz.aab.online",
    "com.oson.app", "com.paynet.android",
}


def known_good(pkg):
    if not pkg:
        return None
    p = pkg.lower()
    if p in TRUSTED_EXACT:
        return pkg
    for pre in TRUSTED_PREFIXES:
        if p.startswith(pre):
            return pkg
    return None

# ObfuscatedSignatures — plaintext (engine stores XOR/hash, equivalent matching)
DECRYPTED_SIGS = [
    ("/commends", "bot_endpoint"),
    # "/message" REMOVED — too generic (Google FIDO gms/.../messagebased, FB SDK, any REST API).
    ("jetski", "jetski_family"),
    ("frida-server", "anti.frida"),
    ("frida/gadget", "anti.frida"),
    ("com.topjohnwu.magisk", "anti.magisk"),
    ("/data/local/tmp/frida-server", "anti.frida"),
    ("android.net.VpnService", "anti.vpn"),
    ("WindowManager.LayoutParams", "overlay.windowmgr"),
    ("TYPE_APPLICATION_OVERLAY", "overlay.type"),
    ("AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED", "overlay.text_grab"),
]
TOKEN_SIGS = {
    "dashapp-v2.org": "dashapp",
    "uzbekchill.com": "uzbekchill",
    "taklifnomatoy_robot": "taklif_bot",
    "botsigner": "bot_internal",
    "botorg": "bot_internal",
    "googleadst": "googleadst_dropper",
    "elrxzx.com": "Ajina.Banker.C2",
    "jytas0m31lxvwkqe42y10ktm": "Ajina.Banker.key",
    "3183701586f97ghynsurermm": "Ajina.Banker.key",
    "ydbllnjd.com": "RoundRift.dropper",
    "ilovekkksfm.com": "RoundRift.C2",
    "sqsmlh2nolpxeadifgnmeodg6uc2mvin": "RoundRift.key",
    "_zl6enckey": "RoundRift.native_sym",
    "/video/dropper.html": "RoundRift.endpoint",
    "/api/upload_sms": "banker.sms_exfil",
    "/api/inject": "banker.overlay_inject",
    "/admin/banks": "banker.admin_panel",
}
_TOKEN_RE = re.compile(r"[A-Za-z0-9._/\-]{4,128}")

# ============================================================
#  DexPatternAnalyzer
# ============================================================
DEX_PATTERNS = [
    ("Ldalvik/system/DexClassLoader;", 30, "DexClassLoader (runtime kod yuklash)"),
    ("Ldalvik/system/InMemoryDexClassLoader;", 40, "InMemoryDexClassLoader (xotirada DEX)"),
    ("Ljava/lang/Runtime;->exec", 25, "Runtime.exec (shell chaqiruv)"),
    ("Landroid/telephony/TelephonyManager;->getDeviceId", 8, "IMEI o'qish"),
    ("Landroid/telephony/TelephonyManager;->getSubscriberId", 10, "IMSI o'qish"),
    ("Landroid/telephony/TelephonyManager;->getSimSerialNumber", 10, "SIM serial o'qish"),
    ("Landroid/telephony/SmsManager;->sendTextMessage", 35, "SMS yuborish API"),
    ("Landroid/telephony/SmsManager;->sendMultipartTextMessage", 35, "Multipart SMS yuborish"),
    ("android.provider.Telephony.SMS_RECEIVED", 20, "SMS qabul intent"),
    ("android.intent.action.NEW_OUTGOING_CALL", 18, "Chiquvchi qo'ng'iroqlarni ushlash"),
    ("Landroid/os/Debug;->isDebuggerConnected", 5, "Anti-debug check"),
    ("TracerPid", 15, "TracerPid /proc anti-debug"),
    ("api.telegram.org/bot", 25, "Telegram bot URL (C2 belgisi)"),
    ("Lcom/bangcle/", 40, "Bangcle packer"),
    ("Lcom/secneo/", 40, "SecNeo packer"),
    ("Lcom/qihoo/", 40, "Qihoo360 packer"),
    ("Lcom/tencent/StubShell", 40, "Tencent Legu packer"),
    ("Lcom/ijiami/", 40, "Ijiami packer"),
    ("android.provider.Telephony.SMS_DELIVER", 25, "SMS_DELIVER (приоритетный перехват)"),
    ("abortBroadcast", 15, "abortBroadcast — SMS перехват и отмена"),
    ("TYPE_APPLICATION_OVERLAY", 12, "Application overlay (banker fake oynasi)"),
    ("TYPE_PHONE", 10, "Eski overlay TYPE_PHONE — banker xattilik belgisi"),
    ("AccessibilityEvent;->getText", 25, "Accessibility orqali matn o'qish (OTP grabber)"),
    ("AccessibilityNodeInfo;->getText", 20, "AccessibilityNode matn o'qish"),
    ("performGlobalAction", 18, "performGlobalAction (Accessibility orqali tap simulyatsiyasi)"),
    ("frida-server", 35, "Anti-Frida check (tahlilga qarshi)"),
    ("frida/gadget", 35, "Anti-Frida gadget probing"),
    ("com.topjohnwu.magisk", 30, "Anti-Magisk (root check)"),
    ("/data/local/tmp/", 12, "Suspicious tmp path probe"),
    ("setMethod(ZipEntry.DEFLATED)", 10, "ZipEntry custom method (ZIP-evasion)"),
    ("getRunningAppProcesses", 5, "getRunningAppProcesses (qaysi ilova ochiq?)"),
    ("UsageStatsManager", 5, "UsageStatsManager (overlay trigger uchun)"),
    ("Landroid/media/projection/MediaProjection;", 8, "MediaProjection (ekran yozish)"),
    ("createVirtualDisplay", 5, "VirtualDisplay (ekran ko'chirish)"),
    ("dispatchGesture", 18, "Programmatik gesture (accessibility hijack)"),
]
EVASION_LABELS = {
    "Anti-debug check",
    "TracerPid /proc anti-debug",
    "Anti-Frida check (tahlilga qarshi)",
    "Anti-Frida gadget probing",
    "Anti-Magisk (root check)",
    "Suspicious tmp path probe",
    "ZipEntry custom method (ZIP-evasion)",
}
MAX_DEX_SIZE = 30 * 1024 * 1024
DEX_SAMPLE = 4 * 1024 * 1024

# ============================================================
#  PermissionCombos
# ============================================================
# Scores recalibrated 2026-05 for FP reduction (capability-breadth combos lowered).
COMBOS = [
    ("SMS-stealer (banking OTP)", 60, {f"{P}READ_SMS", f"{P}INTERNET"}, {f"{P}RECEIVE_SMS", f"{P}SEND_SMS"}),
    ("Overlay phisher (soxta bank oynasi)", 60, {f"{P}SYSTEM_ALERT_WINDOW"}, {f"{P}BIND_ACCESSIBILITY_SERVICE", f"{P}WRITE_SETTINGS"}),
    ("Credential stealer", 12, {f"{P}GET_ACCOUNTS", f"{P}INTERNET"}, {f"{P}AUTHENTICATE_ACCOUNTS", f"{P}READ_CONTACTS"}),
    ("Ransomware/wiper alomati", 45, {f"{P}BIND_DEVICE_ADMIN"}, set()),
    ("Dropper (yangi APK o'rnatadi)", 22, {f"{P}REQUEST_INSTALL_PACKAGES", f"{P}INTERNET"}, set()),
    ("Stealth-spy (mikrofon+kamera+geo)", 15, {f"{P}RECORD_AUDIO", f"{P}CAMERA", f"{P}ACCESS_FINE_LOCATION", f"{P}INTERNET"}, set()),
    ("Call hijacker", 35, {f"{P}CALL_PHONE", f"{P}READ_CALL_LOG"}, {f"{P}PROCESS_OUTGOING_CALLS", f"{P}ANSWER_PHONE_CALLS"}),
    ("Yashirin auto-launch (boot + foreground)", 5, {f"{P}RECEIVE_BOOT_COMPLETED", f"{P}FOREGROUND_SERVICE"}, set()),
    ("Kontakt+lokatsiya o'g'irlash", 8, {f"{P}READ_CONTACTS", f"{P}ACCESS_FINE_LOCATION", f"{P}INTERNET"}, set()),
    ("Notification interception", 30, {f"{P}BIND_NOTIFICATION_LISTENER_SERVICE"}, {f"{P}INTERNET"}),
    ("OTP-grabber (SMS+Accessibility)", 90, {f"{P}READ_SMS", f"{P}BIND_ACCESSIBILITY_SERVICE"}, set()),
    ("Full banker (overlay+a11y+net)", 100, {f"{P}SYSTEM_ALERT_WINDOW", f"{P}BIND_ACCESSIBILITY_SERVICE", f"{P}INTERNET"}, set()),
    ("Persistent botnet (boot+a11y+fg)", 70, {f"{P}RECEIVE_BOOT_COMPLETED", f"{P}BIND_ACCESSIBILITY_SERVICE", f"{P}FOREGROUND_SERVICE"}, set()),
]


def eval_combos(perms):
    s = set(perms)
    matches = []
    for label, score, required, anyof in COMBOS:
        if not required.issubset(s):
            continue
        if anyof and not (anyof & s):
            continue
        matches.append((label, score))
    return matches


# ============================================================
#  ManifestAnalyzer permissions (ApkScanner DANGEROUS_PERMISSIONS)
# ============================================================
DANGEROUS_PERMISSIONS = {
    f"{P}SEND_SMS", f"{P}RECEIVE_SMS", f"{P}READ_SMS", f"{P}CALL_PHONE",
    f"{P}READ_CONTACTS", f"{P}WRITE_CONTACTS", f"{P}ACCESS_FINE_LOCATION",
    f"{P}CAMERA", f"{P}RECORD_AUDIO", f"{P}READ_CALL_LOG", f"{P}WRITE_CALL_LOG",
    f"{P}SYSTEM_ALERT_WINDOW", f"{P}REQUEST_INSTALL_PACKAGES",
}

SMS_RECEIVE_ACTIONS = {
    "android.provider.Telephony.SMS_RECEIVED",
    "android.provider.Telephony.SMS_DELIVER",
    "android.provider.Telephony.WAP_PUSH_RECEIVED",
}

# ============================================================
#  looksRandomPackageName
# ============================================================
SAFE_TLD_SEGMENTS = {
    "com", "org", "net", "io", "uz", "ru", "eu", "us", "co", "uk", "in", "de", "fr",
    "edu", "gov", "mil", "app", "dev", "ai",
    "google", "android", "androidx", "support", "fb", "facebook", "telegram", "whatsapp",
}


def shannon_entropy_chars(s):
    if not s:
        return 0.0
    counts = {}
    for c in s:
        counts[c] = counts.get(c, 0) + 1
    n = float(len(s))
    h = 0.0
    for c in counts.values():
        p = c / n
        h -= p * (math.log(p) / math.log(2.0))
    return h


def looks_random_package(pkg):
    if not pkg:
        return False
    parts = pkg.split(".")
    if len(parts) < 2:
        return False
    candidates = [p for p in parts if len(p) >= 4 and p.lower() not in SAFE_TLD_SEGMENTS]
    if not candidates:
        return False
    vowels = set("aeiouy")
    hits = 0
    for seg in candidates:
        lower = seg.lower()
        vowel_count = sum(1 for c in lower if c in vowels)
        vowel_ratio = vowel_count / len(lower)
        max_cons = run = 0
        for c in lower:
            if c.isalpha() and c not in vowels:
                run += 1
                max_cons = max(max_cons, run)
            else:
                run = 0
        entropy = shannon_entropy_chars(lower)
        cons_threshold = 3 if len(lower) <= 5 else 4
        signals = sum([vowel_ratio < 0.25, max_cons >= cons_threshold, entropy >= 3.7])
        if signals >= 2:
            hits += 1
    return hits >= 1


# ============================================================
#  FilenameHeuristic
# ============================================================
SUSPICIOUS_PATTERNS = [
    (re.compile(r"(?i)\.(mp4|mp3|mov|avi|jpg|jpeg|png|gif|pdf|docx?|xlsx?|zip|rar)\s*(\(\d+\))?\.apk$"), 50, "Double extension trick (file.mp4.apk / file.mp4 (2).apk)"),
    (re.compile(r"(?i)^RASMLAR\s*\(\d+\)\.apk$"), 40, "Telegram-banker template: RASMLAR (NN).apk"),
    (re.compile(r"(?i)^VIDEO\.\d{1,2}\.\d{1,2}\.\d{4}.*\.apk$"), 40, "Telegram-banker template: VIDEO.DD.MM.YYYY"),
    (re.compile(r"(?i)^VID_\d{4,8}_\d{6,10}\.apk$"), 40, "Telegram-banker template: VID_NNN_DATE"),
    (re.compile(r"(?i)to.?ydan[\s_]*fotolar"), 40, "Uzbek lure: toydan fotolar"),
    (re.compile(r"(?i)\.foto\.apk$"), 30, "Soxta '.foto.apk' qo'shimchasi"),
    (re.compile(r"(?i)rasmlar\s*album"), 35, "Uzbek lure: RasmlarAlbum"),
    (re.compile(r"(?i)\brasmlar\s*\(\d+\)"), 30, "Telegram-banker template: Rasmlar (NN)"),
    (re.compile(r"(?i)TAKLIFNOMA"), 35, "Uzbek lure: TAKLIFNOMA"),
    (re.compile(r"(?i)^Video_\d+[_-]\d+"), 30, "Telegram-distributed: Video_NNN_NNN"),
    (re.compile(r"(?i)^\d+_\d{15,}\.apk$"), 25, "Telegram message-id raw name"),
    (re.compile(r"(?i)^[A-F0-9]{16,}\.apk$"), 25, "Hex-only filename"),
    (re.compile(r"[  -​  　ᅟᅠㅤﾠ⁠-⁤⁪-⁯﻿᠋-᠎឴឵]"), 40, "Unicode invisible chars (hide .apk)"),
    (re.compile(r"\(\d+\).*\(\d+\)"), 15, "Multiple numbered brackets"),
]
LURE_KEYWORDS = [
    (re.compile(r"(?i)\b(MTS|MTC|Beeline|Билайн|Bilayn|UCell|Uzmobile|UMS|UzbekTelecom)\b"), 25, "Telecom impersonation lure"),
    (re.compile(r"(?i)\b(kupon|sovrin|sovgha|sovgalar|sovrinli|bonus|podarok|подарок|sovga|prize|gift|lottery|loter|lotery)\b"), 20, "Gift/lottery lure"),
    (re.compile(r"(?i)\b(yangilanish|обновление|update|updater|setup|installer)\b"), 15, "Fake-update lure"),
    (re.compile(r"(?i)\b(crack|cracked|mod[\W_]?apk|premium|hack|hacked|vzlom|взлом|pro[\W_]?version|unlocked)\b"), 20, "Crack/mod/premium lure"),
    (re.compile(r"(?i)\b(porno|porn|sex|xxx|18\+|porno_uz|seks|porno_video)\b"), 25, "Adult-content dropper lure"),
    (re.compile(r"(?i)\b(invoice|receipt|chek|fatura|tilxat|shartnoma|kontrakt|kontract|hisob|hisobnoma)\b"), 20, "Document/invoice lure"),
    (re.compile(r"(?i)\b(soliq|tax|mygov|edo|gov_uz|soliqlar|davlat_xizmat)\b"), 25, "Government impersonation lure"),
]
KNOWN_BRANDS = {
    "telegram": "org.telegram", "whatsapp": "com.whatsapp", "instagram": "com.instagram",
    "facebook": "com.facebook", "messenger": "com.facebook.orca",
    "youtube": "com.google.android.youtube", "tiktok": "com.zhiliaoapp.musically",
    "viber": "com.viber", "imo": "com.imo", "signal": "org.thoughtcrime.securesms",
    "uzcard": "uz.uzcard", "humo": "uz.humo", "mytaxi": "uz.mytaxi", "click": "uz.click",
    "payme": "uz.dida.payme", "anorbank": "uz.anorbank", "tbcuz": "uz.tbc",
    "kapitalbank": "uz.kapitalbank", "asaka": "uz.asaka", "agrobank": "uz.agrobank",
    "ipakyo": "uz.ipakyulibank", "qishloq": "uz.qishloqqurilishbank",
    "mygov": "uz.mygov", "soliq": "uz.soliq", "edo": "uz.edo",
    "gmail": "com.google.android.gm", "chrome": "com.android.chrome",
    "drive": "com.google.android.apps.docs",
}
COMMON_BIGRAMS = set("""th he in er an re on at en nd ti es or te of ed is it al ar st to nt ng se ha as ou io le
ve co me de hi ri ro ic ne ea ra ce li ch ll be ma si om ur ap pl ol et pa ge il us vi ad lo do
uz ek im rg""".split())


def _levenshtein(a, b, maxd):
    if a == b:
        return 0
    if abs(len(a) - len(b)) > maxd:
        return maxd + 1
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        rowmin = i
        for j in range(1, len(b) + 1):
            cur[j] = prev[j - 1] if a[i - 1] == b[j - 1] else 1 + min(prev[j - 1], prev[j], cur[j - 1])
            rowmin = min(rowmin, cur[j])
        prev = cur
        if rowmin > maxd:
            return maxd + 1
    return prev[len(b)]


def _match_brand(brand, tokens):
    if brand in tokens:
        return ("exact", brand)
    if len(brand) < 5:
        return None
    for tok in tokens:
        if not (len(brand) - 1 <= len(tok) <= len(brand) + 2):
            continue
        if tok == brand:
            return ("exact", tok)
        d = _levenshtein(tok, brand, 2)
        if 1 <= d <= 2:
            return ("typosquat", tok)
    return None


def _mixed_script_token(name):
    for tok in re.findall(r"[^\W\d_]+", name, re.UNICODE):
        if len(tok) < 4:
            continue
        lat = cyr = 0
        for c in tok:
            o = ord(c)
            if 0x0400 <= o <= 0x04FF or 0x0500 <= o <= 0x052F:
                cyr += 1
            elif ("A" <= c <= "Z") or ("a" <= c <= "z"):
                lat += 1
        if lat >= 1 and cyr >= 1:
            return tok
    return None


def _bigram_rarity(s):
    if len(s) < 3:
        return 0.0
    total = rare = 0
    for i in range(len(s) - 1):
        bg = s[i:i + 2]
        if len(bg) != 2 or not bg.isalpha():
            continue
        total += 1
        if bg not in COMMON_BIGRAMS:
            rare += 1
    return rare / total if total else 0.0


def filename_heuristic(apk_path, package_name, app_label=None):
    filename = os.path.basename(apk_path)
    flags = []
    score = 0
    hard = None

    for regex, s, label in SUSPICIOUS_PATTERNS:
        if regex.search(filename):
            score += s
            flags.append(label)
    for regex, s, label in LURE_KEYWORDS:
        if regex.search(filename):
            score += s
            flags.append(label)

    mixed = _mixed_script_token(filename)
    if mixed:
        score += 80
        flags.append(f'Mixed Cyrillic-Latin: "{mixed}" (homoglyph)')
        hard = ("homoglyph", mixed)

    lower_name = filename.rsplit(".", 1)[0].lower() if "." in filename else filename.lower()
    tokens = set(re.findall(r"[a-z][a-z0-9]{2,}", lower_name))

    brand_hit = None
    for brand, expected in KNOWN_BRANDS.items():
        m = _match_brand(brand, tokens)
        if not m:
            continue
        kind, matched = m
        pkg_ok = bool(package_name) and package_name.lower().startswith(expected.lower())
        if not pkg_ok:
            brand_hit = ("brand", brand, expected, package_name, kind)
            flags.append(f'Brand impersonation ({kind}): "{brand}" but pkg={package_name}')
            score += 80
            break
    if brand_hit:
        hard = brand_hit

    if app_label:
        label_tokens = set(re.findall(r"[a-zа-я][a-zа-я0-9]{2,}", app_label.lower()))
        for brand in KNOWN_BRANDS:
            if (brand in label_tokens) != (brand in tokens):
                score += 35
                flags.append(f'Label/filename mismatch: app "{app_label}" vs file "{filename}"')
                break

    if package_name:
        for seg in [s for s in package_name.split(".") if len(s) >= 4]:
            r = _bigram_rarity(seg.lower())
            if r >= 0.55:
                score += 20
                flags.append(f"Package segment '{seg}' bigram-rarity={r:.2f}")
                break

    return score, flags, hard


# ============================================================
#  ZipEncryptionDetector (GP-flag bit-0 on LFH + CD)
# ============================================================
def zip_encryption(apk_path, max_headers=5000):
    encrypted = 0
    total = 0
    sample = []
    try:
        with open(apk_path, "rb") as f:
            data = f.read()
        n = len(data)
        # local file headers
        pos = 0
        while pos + 30 < n and total < max_headers:
            if data[pos:pos + 4] != b"PK\x03\x04":
                break
            total += 1
            gp = struct.unpack_from("<H", data, pos + 6)[0]
            comp_size = struct.unpack_from("<I", data, pos + 18)[0]
            name_len = struct.unpack_from("<H", data, pos + 26)[0]
            extra_len = struct.unpack_from("<H", data, pos + 28)[0]
            if gp & 0x0001:
                encrypted += 1
                if len(sample) < 5 and 1 <= name_len <= 512:
                    sample.append(data[pos + 30:pos + 30 + name_len].decode("utf-8", "replace"))
            advance = 30 + name_len + extra_len + comp_size
            if advance <= 0:
                break
            pos += advance
        # central directory
        eocd = data.rfind(b"PK\x05\x06")
        if eocd >= 0:
            cd_size = struct.unpack_from("<I", data, eocd + 12)[0]
            cd_off = struct.unpack_from("<I", data, eocd + 16)[0]
            cd_pos = cd_off
            cd_scanned = 0
            cd_end = min(cd_off + cd_size, n)
            while cd_pos + 46 < cd_end and cd_scanned < max_headers:
                if data[cd_pos:cd_pos + 4] != b"PK\x01\x02":
                    break
                cd_scanned += 1
                gp = struct.unpack_from("<H", data, cd_pos + 8)[0]
                name_len = struct.unpack_from("<H", data, cd_pos + 28)[0]
                extra_len = struct.unpack_from("<H", data, cd_pos + 30)[0]
                comment_len = struct.unpack_from("<H", data, cd_pos + 32)[0]
                if gp & 0x0001:
                    encrypted += 1
                    if len(sample) < 5 and 1 <= name_len <= 512:
                        nm = data[cd_pos + 46:cd_pos + 46 + name_len].decode("utf-8", "replace")
                        if nm not in sample:
                            sample.append(nm)
                cd_pos += 46 + name_len + extra_len + comment_len
            if cd_scanned > total:
                total = cd_scanned
    except Exception:
        pass
    return encrypted, total, sample


# ============================================================
#  Raw-ZIP based analyzers (DEX patterns, dropper, native, sigs, manifest actions)
# ============================================================
ALLOWED_SO_ABIS = {"armeabi-v7a", "arm64-v8a", "x86", "x86_64", "armeabi", "mips", "mips64"}
STRONG_IMPORTS = ["execve", "execvp", "execlp", "/system/bin/sh", "/bin/sh",
                  "/system/bin/su", "/data/local/tmp", "ptrace", "/proc/self/maps"]
SAFE_LIB_NAMES = ["libflutter.so", "libreactnativejni.so", "libhermes.so", "libjsc.so",
                  "libv8", "libunity.so", "libil2cpp.so", "libmonochrome.so", "libchrome.so",
                  "libwebviewchromium.so", "libcrashlytics", "libtensorflow", "libpytorch",
                  "libfb.so", "libfolly", "libcronet", "libmmkv.so", "libtool-checker.so"]


def byte_entropy(data):
    if not data:
        return 0.0
    counts = [0] * 256
    for b in data:
        counts[b] += 1
    nn = float(len(data))
    h = 0.0
    ln2 = math.log(2.0)
    for c in counts:
        if c:
            p = c / nn
            h -= p * (math.log(p) / ln2)
    return h


def analyze_raw_zip(apk_path):
    """Returns dict with dex/dropper/native/obfsig findings. Uses raw zipfile (like the engine)."""
    out = {
        "dex_patterns": [], "dex_score": 0, "packer": None, "too_many_dex": False,
        "hidden_apks": [], "hidden_dex": [], "hidden_elf": [], "so_outside_lib": [],
        "encrypted_payloads": [], "dropper_score": 0,
        "native_suspicious": [], "native_reasons": [], "native_score": 0,
        "sigs": [],
    }
    try:
        zf = zipfile.ZipFile(apk_path)
    except Exception:
        return out
    with zf:
        infos = zf.infolist()

        # ---- DexPatternAnalyzer ----
        dex_entries = [e for e in infos if e.filename.startswith("classes") and e.filename.endswith(".dex") and not e.is_dir()]
        out["too_many_dex"] = len(dex_entries) > 5
        seen_labels = set()
        for e in dex_entries:
            if e.file_size <= 0 or e.file_size > MAX_DEX_SIZE:
                continue
            try:
                with zf.open(e) as fp:
                    data = fp.read(DEX_SAMPLE)
            except Exception:
                continue
            text = data.decode("latin-1", "replace")
            for needle, score, label in DEX_PATTERNS:
                if score <= 0:
                    continue
                if needle in text and label not in seen_labels:
                    seen_labels.add(label)
                    out["dex_patterns"].append(label)
                    out["dex_score"] += score
                    if out["packer"] is None and label.endswith("packer"):
                        out["packer"] = label
        if out["too_many_dex"]:
            out["dex_patterns"].append(f"{len(dex_entries)} ta DEX fayl")
            out["dex_score"] += 15

        # ---- DropperDetector ----
        for e in infos:
            if e.is_dir() or e.file_size <= 4:
                continue
            name = e.filename
            if re.match(r"^classes\d*\.dex$", name):
                continue
            if name == "AndroidManifest.xml":
                continue
            if name.startswith("META-INF/") and not name.endswith(".dex") and not name.endswith(".apk"):
                continue
            if name.startswith("res/") and not name.startswith("res/raw/"):
                continue
            if name.endswith(".so") and not _in_valid_lib(name):
                out["so_outside_lib"].append(name)
                continue
            try:
                with zf.open(e) as fp:
                    magic = fp.read(8)
            except Exception:
                continue
            if len(magic) < 4:
                continue
            if magic.startswith(b"PK\x03\x04") and _is_payload_loc(name) and _looks_like_embedded_apk(zf, e, name):
                out["hidden_apks"].append(name)
            elif magic.startswith(b"dex\n"):
                out["hidden_dex"].append(name)
            elif magic.startswith(b"\x7fELF") and not name.endswith(".so"):
                out["hidden_elf"].append(name)
            else:
                if _suspect_encrypted(name, e.file_size):
                    try:
                        with zf.open(e) as fp:
                            sample = fp.read(64 * 1024)
                        if len(sample) >= 1024 and byte_entropy(sample) >= 7.5:
                            out["encrypted_payloads"].append(name)
                    except Exception:
                        pass
        out["dropper_score"] = (len(out["hidden_apks"]) + len(out["hidden_dex"]) +
                                len(out["hidden_elf"]) + len(out["so_outside_lib"])) * 40 + \
                               len(out["encrypted_payloads"]) * 60

        # ---- NativeLibAnalyzer ----
        for e in infos:
            if not e.filename.lower().endswith(".so") or e.is_dir():
                continue
            if e.file_size <= 0 or e.file_size > 5 * 1024 * 1024:
                continue
            base = e.filename.rsplit("/", 1)[-1].lower()
            if any(base.startswith(s) or base == s for s in SAFE_LIB_NAMES):
                continue
            try:
                with zf.open(e) as fp:
                    data = fp.read(256 * 1024)
            except Exception:
                continue
            if not data:
                continue
            text = data.decode("latin-1", "replace")
            hits = [s for s in STRONG_IMPORTS if s in text]
            ent = byte_entropy(data)
            if hits and ent > 7.6:
                out["native_suspicious"].append(e.filename)
                out["native_score"] += 45
                out["native_reasons"].append(f"Native {e.filename}: {hits} + entropy {ent:.2f}")
            elif len(hits) >= 2:
                out["native_suspicious"].append(e.filename)
                out["native_score"] += 40
                out["native_reasons"].append(f"Native {e.filename}: {hits}")
        out["native_score"] = min(out["native_score"], 60)

        # ---- ObfuscatedSignatures ----
        sigs = set()
        # DEX entries fully (up to 8MB) + other entries (256, 1MB)
        dex_full = [e for e in infos if e.filename.startswith("classes") and e.filename.endswith(".dex") and not e.is_dir()]
        others = [e for e in infos if not e.is_dir() and e not in dex_full and e.file_size > 0]
        scan_list = [(e, 8 * 1024 * 1024) for e in dex_full] + [(e, 1024 * 1024) for e in others[:256]]
        for e, maxb in scan_list:
            try:
                with zf.open(e) as fp:
                    data = fp.read(maxb)
            except Exception:
                continue
            text = data.decode("latin-1", "replace")
            low = text.lower()
            for sig, label in DECRYPTED_SIGS:
                if sig.lower() in low:
                    sigs.add(label)
            for m in _TOKEN_RE.finditer(text):
                tok = m.group(0).lower()
                if tok in TOKEN_SIGS:
                    sigs.add(TOKEN_SIGS[tok])
        out["sigs"] = sorted(sigs)
    return out


def _looks_like_embedded_apk(zf, entry, name):
    """A PK.. entry in a payload location is a real embedded APK/JAR only if it
    contains AndroidManifest.xml/.dex, or its name ends with .apk/.jar. Plain
    data-zips (Samsung tzdata distro.zip, game asset bundles) are NOT droppers."""
    lower = name.lower()
    if lower.endswith(".apk") or lower.endswith(".jar"):
        return True
    try:
        with zf.open(entry) as fp:
            ib = fp.read(64 * 1024 * 1024)
        inner = zipfile.ZipFile(io.BytesIO(ib))
        names = inner.namelist()
        return ("AndroidManifest.xml" in names) or any(n.endswith(".dex") for n in names)
    except Exception:
        return False


def _in_valid_lib(name):
    if not name.startswith("lib/"):
        return False
    parts = name.split("/")
    return len(parts) >= 3 and parts[1] in ALLOWED_SO_ABIS


def _is_payload_loc(name):
    return (name.startswith("assets/") or name.startswith("res/raw/") or
            name.startswith("META-INF/") or "/" not in name)


def _suspect_encrypted(name, size):
    if size < 100 * 1024:
        return False
    if not name.startswith("assets/") and not name.startswith("res/raw/"):
        return False
    lower = name.lower()
    for ext in (".mp3", ".mp4", ".m4a", ".m4v", ".ogg", ".opus", ".webm", ".aac",
                ".png", ".jpg", ".jpeg", ".gif", ".webp", ".avif", ".heic",
                ".ttf", ".otf", ".woff", ".woff2", ".zip", ".7z", ".gz", ".xz",
                ".bz2", ".so", ".dex", ".apk", ".pdf"):
        if lower.endswith(ext):
            return False
    return True


def manifest_global_actions(apk_path):
    found = set()
    try:
        with zipfile.ZipFile(apk_path) as zf:
            data = zf.read("AndroidManifest.xml")
    except Exception:
        return found
    candidates = [
        "android.provider.Telephony.SMS_RECEIVED",
        "android.provider.Telephony.SMS_DELIVER",
        "android.provider.Telephony.WAP_PUSH_RECEIVED",
        "android.intent.action.BOOT_COMPLETED",
        "android.app.action.DEVICE_ADMIN_ENABLED",
    ]
    text = data.decode("latin-1", "replace")
    for action in candidates:
        if action.encode("utf-16-le") in data or action in text:
            found.add(action)
    return found


# ============================================================
#  androguard-backed metadata (package, perms, components, cert)
# ============================================================
def cert_sha256(apk):
    """SHA-256 of signing cert DER — mirror of Android Signature.toByteArray()."""
    for getter in ("get_certificates_der_v3", "get_certificates_der_v2", "get_certificates_der_v1"):
        try:
            fn = getattr(apk, getter, None)
            if fn is None:
                continue
            ders = fn()
            if ders:
                return hashlib.sha256(ders[0]).hexdigest()
        except Exception:
            continue
    # fallback: asn1 certs
    try:
        certs = apk.get_certificates()
        if certs:
            return hashlib.sha256(certs[0].dump()).hexdigest()
    except Exception:
        pass
    return None


def manifest_analysis(apk, apk_path):
    """ManifestAnalyzer port. Returns (score, red, orange, a11y, device_admin, exported_count)."""
    red, orange = [], []
    a11y = device_admin = False

    actions = manifest_global_actions(apk_path)
    if actions & SMS_RECEIVE_ACTIONS:
        red.append("SMS qabul qiluvchi (high priority)")
    if "android.intent.action.BOOT_COMPLETED" in actions:
        orange.append("BOOT_COMPLETED qabul qiluvchi")
    if "android.app.action.DEVICE_ADMIN_ENABLED" in actions:
        device_admin = True
        red.append("DeviceAdmin receiver")

    # app flags + components via manifest XML
    exported = 0
    if apk is not None:
        try:
            mx = apk.get_android_manifest_xml()
        except Exception:
            mx = None
        # app flags
        try:
            app_el = mx.find("application") if mx is not None else None
        except Exception:
            app_el = None
        if app_el is not None:
            def attr(el, key):
                return el.get("{http://schemas.android.com/apk/res/android}" + key)
            ab = attr(app_el, "allowBackup")
            if ab is None or ab.lower() == "true":  # default true
                orange.append("allowBackup=true")
            dbg = attr(app_el, "debuggable")
            if dbg is not None and dbg.lower() == "true":
                red.append("debuggable=true")
            ct = attr(app_el, "usesCleartextTraffic")
            if ct is not None and ct.lower() == "true":
                orange.append("usesCleartextTraffic=true")
        # accessibility service + exported
        if mx is not None:
            ANS = "{http://schemas.android.com/apk/res/android}"
            for svc in mx.iter("service"):
                if svc.get(ANS + "permission") == "android.permission.BIND_ACCESSIBILITY_SERVICE":
                    a11y = True
            for tag in ("activity", "service", "receiver", "provider"):
                for el in mx.iter(tag):
                    exp = el.get(ANS + "exported")
                    perm = el.get(ANS + "permission")
                    has_filter = el.find("intent-filter") is not None
                    is_exported = (exp == "true") or (exp is None and has_filter)
                    if is_exported and not perm:
                        exported += 1
    if a11y:
        red.append("Accessibility xizmati (banking trojan vektori)")
    if exported >= 5:
        orange.append(f"{exported} ta eksport qilingan komponent himoyasiz")

    score = len(red) * 30 + len(orange) * 10
    return score, red, orange, a11y, device_admin, exported


# ============================================================
#  Main scan() — mirrors ApkScanner.scan() verdict combinator
# ============================================================
def scan(apk_path, sensitivity="medium"):
    res = {
        "file": os.path.basename(apk_path),
        "verdict": "SAFE", "reason": "", "score": 0,
        "package": None, "cert": None, "apk_sha256": None,
        "fired": [], "perms": [], "dangerous": [],
        "family": None,
    }

    # 0) APK file SHA-256 → hash blacklist
    try:
        h = hashlib.sha256()
        with open(apk_path, "rb") as f:
            for chunk in iter(lambda: f.read(65536), b""):
                h.update(chunk)
        res["apk_sha256"] = h.hexdigest()
    except Exception:
        pass
    fam = MALICIOUS_HASHES.get((res["apk_sha256"] or "").lower())
    if fam:
        res.update(verdict="DANGER", reason=f"hash blacklist: {fam}", family=fam)
        res["fired"].append("hash-blacklist")
        return res

    # 0b) ZIP encryption flag
    enc, total, enc_sample = zip_encryption(apk_path)
    if enc > 0:
        res.update(verdict="DANGER", reason=f"ZIP encryption evasion ({enc}/{total})")
        res["fired"].append("zip-encryption")
        return res

    # cert fingerprint + package via androguard
    apk = None
    if _HAVE_AG:
        try:
            apk = APK(apk_path)
        except Exception:
            apk = None
    if apk is not None:
        try:
            res["cert"] = cert_sha256(apk)
        except Exception:
            pass
        try:
            res["package"] = apk.get_package()
        except Exception:
            pass
        try:
            res["perms"] = list(apk.get_permissions() or [])
        except Exception:
            pass

    # 1) malicious cert
    fam = MALICIOUS_CERTS.get((res["cert"] or "").lower())
    if fam:
        res.update(verdict="DANGER", reason=f"cert blacklist: {fam}", family=fam)
        res["fired"].append("cert-blacklist")
        return res

    # 1b) malicious package
    fam = MALICIOUS_PACKAGES.get((res["package"] or "").lower())
    if fam:
        res.update(verdict="DANGER", reason=f"pkg blacklist: {fam}", family=fam)
        res["fired"].append("pkg-blacklist")
        return res

    # (2) trusted whitelist — empty in repo, skip

    dangerous = [p for p in res["perms"] if p in DANGEROUS_PERMISSIONS]
    res["dangerous"] = dangerous

    # analyzers
    raw = analyze_raw_zip(apk_path)
    signatures = raw["sigs"]
    m_score, m_red, m_orange, a11y, device_admin, exported = manifest_analysis(apk, apk_path)
    combos = eval_combos(res["perms"])
    combo_score = sum(s for _, s in combos)
    dex_score = raw["dex_score"]
    dropper_score = raw["dropper_score"]
    native_suspicious = raw["native_suspicious"]

    # app label — androguard
    app_label = None
    if apk is not None:
        try:
            app_label = apk.get_app_name()
        except Exception:
            app_label = None
    fn_score, fn_flags, fn_hard = filename_heuristic(apk_path, res["package"], app_label)

    random_pkg = looks_random_package(res["package"])

    evasion_count = sum(1 for p in raw["dex_patterns"] if p in EVASION_LABELS)

    # Current thresholds (recalibrated 2026-05 for FP headroom).
    if sensitivity == "high":
        danger_t, susp_t = 55, 28
    elif sensitivity == "low":
        danger_t, susp_t = 120, 60
    else:
        danger_t, susp_t = 85, 40

    native_score = raw["native_score"]
    # native folded into total (no longer instant DANGER — was main FP source).
    total_score = m_score + combo_score + dex_score + dropper_score + fn_score + native_score
    res["score"] = total_score
    kg = known_good(res["package"])
    strong_combo = any(s >= 90 for _, s in combos)  # OTP-grabber / Full-banker
    if kg:
        res["fired"].append(f"known-good:{kg}")

    # record what fired
    fired = res["fired"]
    if signatures:
        fired.append(f"obfuscated-sigs:{','.join(signatures)}")
    if m_red:
        fired.append(f"manifest-red:{len(m_red)}")
    if a11y:
        fired.append("accessibility")
    if device_admin:
        fired.append("device-admin")
    for label, _ in combos:
        fired.append(f"combo:{label}")
    if raw["dex_patterns"]:
        fired.append(f"dex:{','.join(raw['dex_patterns'][:6])}")
    if raw["hidden_apks"]:
        fired.append(f"hidden-apk:{raw['hidden_apks'][:2]}")
    if raw["hidden_dex"]:
        fired.append(f"hidden-dex:{raw['hidden_dex'][:2]}")
    if raw["hidden_elf"]:
        fired.append(f"hidden-elf:{raw['hidden_elf'][:2]}")
    if raw["so_outside_lib"]:
        fired.append(f"so-outside-lib:{raw['so_outside_lib'][:2]}")
    if raw["encrypted_payloads"]:
        fired.append(f"encrypted-payload:{raw['encrypted_payloads'][:2]}")
    if native_suspicious:
        fired.append(f"native:{native_suspicious[:2]}")
    if fn_flags:
        fired.append(f"filename:{','.join(fn_flags[:4])}")
    if random_pkg:
        fired.append("random-pkg")
    if fn_hard:
        fired.append(f"filename-HARD:{fn_hard[0]}")

    # ---- verdict combinator (mirror of current ApkScanner.scan) ----
    # Hard filename danger (brand impersonation / homoglyph) — returned early in scan().
    if fn_hard is not None:
        res.update(verdict="DANGER", reason=f"filename hard danger: {fn_hard[0]}")
        return res

    # iconMatch (perceptual icon impersonation) cannot run offline → treated as None.
    if raw["hidden_apks"] or raw["hidden_dex"]:
        v, reason = "DANGER", "dropper: yashirin APK/DEX"
    elif raw["encrypted_payloads"] and (raw["so_outside_lib"] or random_pkg):
        v, reason = "DANGER", "shifrlangan payload + signal"
    elif device_admin and combo_score >= 30:
        v, reason = "DANGER", "device-admin + combo"
    elif signatures:
        v, reason = "DANGER", f"ObfuscatedSignatures IoC: {signatures}"
    elif strong_combo:
        v, reason = "DANGER", "kuchli combo (OTP-grabber/Full-banker)"
    elif evasion_count >= 2:
        v, reason = "DANGER", f"{evasion_count} ta anti-analysis"
    elif kg is not None:
        v, reason = "SAFE", f"ishonchli ishlab chiqaruvchi: {kg}"
    elif total_score >= danger_t:
        v, reason = "DANGER", f"score {total_score} >= {danger_t}"
    elif random_pkg and fn_score >= 40:
        v, reason = "DANGER", "random pkg + Telegram lure nomi"   # NEW durable dropper signal
    elif random_pkg and len(dangerous) >= 3:
        v, reason = "DANGER", "random pkg + 3 xavfli ruxsat"
    elif total_score >= susp_t:
        v, reason = "SUSPICIOUS", f"score {total_score} >= {susp_t}"
    elif evasion_count >= 1:
        v, reason = "SUSPICIOUS", "1 ta anti-analysis"
    elif random_pkg and sensitivity != "low":
        v, reason = "SUSPICIOUS", "random package nomi"
    elif len(dangerous) >= 4 and sensitivity == "high":
        v, reason = "SUSPICIOUS", "4+ xavfli ruxsat (high)"
    else:
        v, reason = "SAFE", "kritik belgilar topilmadi"

    res["verdict"] = v
    res["reason"] = reason
    return res


if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("paths", nargs="+")
    ap.add_argument("--sensitivity", default="medium")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    files = []
    for p in args.paths:
        if os.path.isdir(p):
            for fn in sorted(os.listdir(p)):
                if fn.lower().endswith(".apk"):
                    files.append(os.path.join(p, fn))
        elif p.lower().endswith(".apk"):
            files.append(p)

    counts = {"DANGER": 0, "SUSPICIOUS": 0, "SAFE": 0}
    rows = []
    for fp in files:
        try:
            r = scan(fp, args.sensitivity)
        except Exception as e:
            r = {"file": os.path.basename(fp), "verdict": "ERROR", "reason": str(e),
                 "score": 0, "package": None, "fired": [], "dangerous": []}
        counts[r["verdict"]] = counts.get(r["verdict"], 0) + 1
        rows.append(r)
        icon = {"DANGER": "[X]", "SUSPICIOUS": "[!]", "SAFE": "[ ]", "ERROR": "[E]"}.get(r["verdict"], "[?]")
        print(f"{icon} {r['verdict']:11s} score={r['score']:>4} {r['file']}")
        print(f"      pkg={r.get('package')}  reason={r['reason']}")
        if args.verbose and r.get("fired"):
            for f in r["fired"]:
                print(f"        - {f}")
    print("\n=== SUMMARY ===")
    print(f"Total: {len(files)}  DANGER={counts.get('DANGER',0)}  "
          f"SUSPICIOUS={counts.get('SUSPICIOUS',0)}  SAFE={counts.get('SAFE',0)}  "
          f"ERROR={counts.get('ERROR',0)}")
    # list non-DANGER (potential misses)
    misses = [r for r in rows if r["verdict"] not in ("DANGER",)]
    if misses:
        print(f"\n=== NON-DANGER ({len(misses)}) — potential misses ===")
        for r in misses:
            print(f"  {r['verdict']:11s} {r['file']}  pkg={r.get('package')}  score={r['score']}")
