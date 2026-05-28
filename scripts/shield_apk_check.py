#!/usr/bin/env python3
"""
shield_apk_check.py — yig'ilgan release-APK ichidagi classes*.dex'ni o'qib,
himoyalangan markerlar OCHIQ MATN sifatida QOLMAGANINI tekshiradi.

Bu — "strings kiberqalqon.apk | grep" hujumini simulyatsiya qiladi: agar marker
dex'da topilsa => himoya ishlamayapti.

Ishlatish: python scripts/shield_apk_check.py [apk_path]
Apk berilmasa — eng yangi release APK avtomatik topiladi.
"""
import glob
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REL_DIR = os.path.join(ROOT, "ApkGuard", "app", "build", "outputs", "apk", "release")

# SPESIFIK, loyihaga xos IOC'lar — OCHIQ MATN sifatida BO'LMASLIGI SHART
# (Shield / ObfuscatedSignatures precompute bilan yashirilgan). Bular sirli intel:
# aynan qaysi namuna/paket/sertifikat/C2-domen/kalitni biz bilamiz.
SHOULD_BE_ABSENT = [
    # IOC bazalari (Shield):
    "5db8a5668648061fc388a43df97863a1be3cc9b20764cf5b752b5357a976c307",  # taklifnoma cert
    "75dd6895575576c0e83706c07c226cb16d23174e2372d8217626c95797e1b215",  # Ajina hash
    "com.lzthzvxte.xazoalzxhr",  # Ajina package
    "uzbekchill.com",            # dropper domeni
    # SecurityGuard self-defense markerlari (Shield):
    "de.robv.android.xposed.XposedBridge", "org.lsposed.manager",
    "com.devadvance.rootcloak", "eu.chainfire.supersu",
    # ObfuscatedSignatures C2 domenlari / kalitlari / endpointlari (precompute):
    "elrxzx.com", "ilovekkksfm.com", "ydbllnjd.com", "dashapp-v2.org",
    "JYTAs0m31lxvwkQE42Y10Ktm", "sqsmlH2NOLPXeaDIFGnMEOdG6Uc2mVin",
    "/api/upload_sms", "/api/inject", "/video/dropper.html",
]

# GENERIK indikatorlar — skanerning detekt-pattern'lari (boshqa ilovalarda QIDIRADI).
# Bular ataylab ochiq qoladi: ular umumiy, jamoatchilikka ma'lum (har bir AV
# frida/magisk/su qidiradi) va ularni yashirish — teatr (o'nlab public-API pattern
# baribir qoladi). Faqat ma'lumot uchun ko'rsatamiz, FAIL emas.
INFORMATIONAL = [
    "frida", "magisk", "com.topjohnwu.magisk", "/system/bin/su", "frida-server",
]


def find_apk():
    if len(sys.argv) > 1:
        return sys.argv[1]
    apks = glob.glob(os.path.join(REL_DIR, "*.apk"))
    if not apks:
        return None
    return max(apks, key=os.path.getmtime)


def main():
    apk = find_apk()
    if not apk or not os.path.exists(apk):
        print(f"APK topilmadi: {REL_DIR}\\*.apk — avval `gradlew assembleRelease`")
        sys.exit(2)
    print(f"APK: {os.path.basename(apk)}")

    dex = bytearray()
    with zipfile.ZipFile(apk) as z:
        names = [n for n in z.namelist() if n.endswith(".dex")]
        for n in names:
            dex += z.read(n)
    print(f"DEX fayllar: {', '.join(names)}  ({len(dex)} bayt jami)\n")

    print("== SPESIFIK IOC (yashirin bo'lishi SHART) ==")
    leaked = []
    for m in SHOULD_BE_ABSENT:
        present = m.encode("utf-8") in dex
        print(f"  [{'LEAK!  ' if present else 'hidden '}] {m}")
        if present:
            leaked.append(m)

    print("\n== GENERIK detekt-pattern'lar (ma'lumot uchun, FAIL emas) ==")
    for m in INFORMATIONAL:
        present = m.encode("utf-8") in dex
        print(f"  [{'present' if present else 'absent '}] {m}")

    print()
    if leaked:
        print(f"DIQQAT — {len(leaked)} ta SPESIFIK IOC ochiq matnda qoldi:")
        for m in leaked:
            print("   -", m)
        sys.exit(1)
    print("OK — barcha spesifik IOC dex'da ochiq matnda YO'Q (strings/grep ko'rmaydi).")


if __name__ == "__main__":
    main()
