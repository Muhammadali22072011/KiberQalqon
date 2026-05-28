"""
Re-simulate ApkScanner verdict logic AFTER fixes.
Confirms that virus_sample.apk + Video_*.apk now get DANGER (was SUSPICIOUS).
"""
import zipfile, os, io, sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

PATTERNS = [
    ('Ldalvik/system/DexClassLoader;', 30, 'DexClassLoader runtime kod yuklash'),
    ('Ldalvik/system/InMemoryDexClassLoader;', 40, 'InMemoryDexClassLoader xotirada DEX'),
    ('Landroid/telephony/SmsManager;->sendTextMessage', 35, 'SMS yuborish API'),
    ('Landroid/os/Debug;->isDebuggerConnected', 10, 'Anti-debug check'),
    ('TracerPid', 15, 'TracerPid /proc anti-debug'),
    ('frida-server', 35, 'Anti-Frida check (tahlilga qarshi)'),
    ('frida/gadget', 35, 'Anti-Frida gadget probing'),
    ('com.topjohnwu.magisk', 30, 'Anti-Magisk (root check)'),
    ('/data/local/tmp/', 15, 'Suspicious tmp path probe'),
    ('Landroid/net/VpnService;', 15, 'VpnService check (anti-VPN)'),
    ('setMethod(ZipEntry.DEFLATED)', 10, 'ZipEntry custom method (ZIP-evasion)'),
    ('Ljava/lang/reflect/Method;', 5, 'Reflection API'),
]

# Yangi (tuzatilgandan keyingi) threshold'lar
DANGER_THRESHOLD = 65
SUSPICIOUS_THRESHOLD = 30

EVASION_LABELS = {
    "Anti-debug check",
    "TracerPid /proc anti-debug",
    "Anti-Frida check (tahlilga qarshi)",
    "Anti-Frida gadget probing",
    "Anti-Magisk (root check)",
    "Suspicious tmp path probe",
    "VpnService check (anti-VPN)",
    "ZipEntry custom method (ZIP-evasion)",
}


def analyze(apk_path):
    found = []
    try:
        with zipfile.ZipFile(apk_path) as z:
            for entry in z.infolist():
                if entry.filename.startswith('classes') and entry.filename.endswith('.dex'):
                    if 0 < entry.file_size <= 30 * 1024 * 1024:
                        try:
                            with z.open(entry) as f:
                                data = f.read(4 * 1024 * 1024)
                        except RuntimeError:
                            return None, 'encrypted'
                        text = data.decode('latin-1', errors='replace')
                        for needle, score, label in PATTERNS:
                            if needle in text and label not in [f[0] for f in found]:
                                found.append((label, score))
    except Exception as e:
        return None, str(e)
    return found, None


def verdict(found):
    if found is None:
        return 'DANGER (ZipEncryptionDetector)'
    score = sum(s for _, s in found)
    evasion_count = sum(1 for label, _ in found if label in EVASION_LABELS)
    if evasion_count >= 2:
        return f'DANGER (evasion combo: {evasion_count})'
    if score >= DANGER_THRESHOLD:
        return f'DANGER (score={score})'
    if score >= SUSPICIOUS_THRESHOLD:
        return f'SUSPICIOUS (score={score})'
    if evasion_count >= 1:
        return f'SUSPICIOUS (1 evasion pattern)'
    return f'SAFE (score={score})'


target = [f for f in os.listdir('.') if f.endswith('.apk') and any(
    p in f.lower() for p in ['virus.apk', 'virus_sample', 'taklif', 'video_', 'kiberqalqon-1779790']
)]

for apk in target:
    print(f'{"="*70}')
    print(f'APK: {apk}')
    found, err = analyze(apk)
    if err == 'encrypted':
        print(f'  Encrypted DEX → ZipEncryptionDetector hits → DANGER')
        print(f'  VERDICT: DANGER ✓')
        print()
        continue
    if found is not None:
        for label, score in found:
            marker = ' [EVASION]' if label in EVASION_LABELS else ''
            print(f'  +{score:3d}  {label}{marker}')
    v = verdict(found)
    print(f'  VERDICT: {v}')
    print()
