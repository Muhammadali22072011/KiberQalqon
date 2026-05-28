#!/usr/bin/env python3
"""
shield_encode.py — KiberQalqon Shield shifr generatori.

Bu skript com.kiberqalqon.Shield Kotlin obyekti bilan BAYT-MA-BAYT mos
keladigan keystream-XOR shifrini amalga oshiradi va IOC bazalari hamda
SecurityGuard markerlari uchun tayyor (paste qilinadigan) hex-literallarni
chiqaradi.

Shifr:
    KEY        = SHA256(utf8(P1 + P2 + P3))
    keystream  = SHA256(KEY || counter_big_endian_4)  bloklar, 32 bayt har biri
    cipher[i]  = plain_utf8[i] XOR keystream[i]
    chiqish    = lowercase hex(cipher)

Yangi IOC qo'shish:
    python scripts/shield_encode.py "5db8a566..."        # bitta string
    python scripts/shield_encode.py --all                # barcha bazalarni qayta chiqarish

DIQQAT: P1/P2/P3 yoki algoritm o'zgarsa — Shield.kt ham AYNAN shunday
o'zgarishi shart, aks holda barcha eski literallar buziladi.
"""
import hashlib
import sys

# Shield.kt'dagi P1/P2/P3 bilan AYNAN bir xil bo'lishi shart.
P1 = "qz7"
P2 = "4fx9"
P3 = "2k"

_KEY = hashlib.sha256((P1 + P2 + P3).encode("utf-8")).digest()


def _keystream(n: int) -> bytes:
    out = bytearray()
    counter = 0
    while len(out) < n:
        out += hashlib.sha256(_KEY + counter.to_bytes(4, "big")).digest()
        counter += 1
    return bytes(out[:n])


def enc(plain: str) -> str:
    data = plain.encode("utf-8")
    ks = _keystream(len(data))
    return bytes(a ^ b for a, b in zip(data, ks)).hex()


def dec(hexstr: str) -> str:
    data = bytes.fromhex(hexstr.strip())
    ks = _keystream(len(data))
    return bytes(a ^ b for a, b in zip(data, ks)).decode("utf-8")


# ── IOC bazalari (plaintext manba) ────────────────────────────────────────────
# Kalitlar lowercase saqlanadi (lookup .lowercase() qiladi).

MALICIOUS_HASHES = [
    ("75dd6895575576c0e83706c07c226cb16d23174e2372d8217626c95797e1b215", "Ajina.Banker.lzthzvxte"),
    ("8d2f128ccae146e5a991e26c45ffc2c0d7cc9dd999338424c911b03e0805599b", "Ajina.Banker.oktgkst"),
    ("4860aafa2244b0430260d185301f26ac5e3c942f9e60c9fd6b66a322e6d407e9", "Ajina.Banker.yzsfnie"),
    ("7037735359ef99dbc4a6827fe056738f19b93f16ec732d871df260cd4458e9af", "Ajina.Banker.nzolcwh"),
    ("74da27210bcd1f068901988c1ff3f8aa9a40dfff1a60a312947e3e47f195d61b", "Ajina.Banker.boyauosdti"),
    ("b4711ea642a97eac10e41c1fa6316ea4ff484621e2635b3228fe9c212a45c4a5", "Ajina.Banker.clnvwyro"),
    ("b7c1119c9175c24a6631edce74dcbb181a1e84e3cb59cb8d4fafdf3c2fbef93f", "Ajina.Banker.rsewozxrkn"),
    ("243b28ff0ac9fd37ce5c6460d844bc8485aeb7e02497b81421384786da4e49dc", "Ajina.Banker.vngoocackr"),
    ("b77afa48cd87c4ede852fde67ed3858c158db64623cfaecbfdc318bc64ba81a2", "Ajina.Banker.labscleaner"),
    ("06869e214a8fbc869f88e0f0fbeca315f06389891ac1e3c6ad10152dcaf90f66", "Ajina.Banker.uzyjrglm"),
    ("97dc105ddd1d5f185d26c0a0eb09be495121936c5d63e1c506a5c89cf0415a06", "Ajina.Banker.rhoeinmfts"),
    ("76ecc28e5023512879a0c521fc7a9e06d689ab27fd150dca664a6543475d6d92", "Ajina.Banker.gkljgfgha"),
    ("727c1f787bd1768da41a669d1285bdbf5ddf9c3754eb9fe9262a3973da422489", "Ajina.Banker.osrbizoauk"),
    ("6ca17abd38dc8970aeaff85dc38b97974d3445b3c877f7dc6b1a52045315cc43", "RoundRift.ydbllnjd"),
    ("5ed26a060cd95e0124be12f7d94afe1f10880e88d6572323e50b571d18f7c174", "Uzbek-dropper.vudgi"),
    ("de763b5b816deb14a1b9333469c90f3f2743bcacb8c96d0ad1b9397b28e2115b", "Uzbek-dropper.taklifnoma"),
]

MALICIOUS_PACKAGES = [
    ("com.lzthzvxte.xazoalzxhr", "Ajina.Banker"),
    ("com.oktgkst.rrcpkge", "Ajina.Banker"),
    ("com.yzsfnie.sjsztphpis", "Ajina.Banker"),
    ("com.puhfvysb.nzbftunmqq", "Ajina.Banker"),
    ("com.nzolcwh.jdzkycd", "Ajina.Banker"),
    ("com.boyauosdti.ewqejxsd", "Ajina.Banker"),
    ("com.clnvwyro.jjewziwhq", "Ajina.Banker"),
    ("com.rsewozxrkn.fpnsatj", "Ajina.Banker"),
    ("com.vngoocackr.epstfh", "Ajina.Banker"),
    ("com.uzyjrglm.qcvcwxa", "Ajina.Banker"),
    ("com.rhoeinmfts.ctwmqgb", "Ajina.Banker"),
    ("com.gkljgfgha.xitdqvgi", "Ajina.Banker"),
    ("com.osrbizoauk.hihsrugbo", "Ajina.Banker"),
    ("org.labs.cleaner", "Ajina.Banker"),
    ("ydbllnjd.com", "RoundRift"),
    ("pyw.kzxwc", "Uzbek-dropper.vudgi"),
    ("uzbekchill.com", "Uzbek-dropper.taklifnoma"),
]

MALICIOUS_CERTS = [
    ("5db8a5668648061fc388a43df97863a1be3cc9b20764cf5b752b5357a976c307", "Uzbek-dropper.taklifnoma"),
    ("954ee710c4419d1be570a9874e5460650c014f9897cc454b3da02bc80c8ab42d", "Uzbek-dropper.vudgi"),
]

# ── SecurityGuard markerlari (grep-qiymati yuqori) ────────────────────────────

SG_SU_PATHS = [
    "/system/bin/su", "/system/xbin/su", "/sbin/su",
    "/system/sd/xbin/su", "/system/bin/failsafe/su",
    "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su",
    "/su/bin/su", "/odm/bin/su", "/vendor/bin/su", "/product/bin/su",
]

SG_ROOT_APPS = [
    "com.koushikdutta.superuser", "com.thirdparty.superuser",
    "eu.chainfire.supersu", "com.topjohnwu.magisk",
    "com.kingroot.kinguser", "com.kingo.root",
    "com.zachspong.temprootremovejb", "com.ramdroid.appquarantine",
]

SG_ROOT_CLOAKERS = [
    "com.devadvance.rootcloak", "com.devadvance.rootcloakplus",
    "de.robv.android.xposed.installer", "com.saurik.substrate",
    "com.zachspong.temprootremovejb", "com.amphoras.hidemyroot",
    "com.formyhm.hideroot",
]

SG_MAGISK_FILES = [
    "/sbin/.magisk", "/data/adb/magisk", "/cache/.disable_magisk",
    "/dev/.magisk.unblock", "/data/adb/modules", "/init.magisk.rc",
]

SG_FRIDA_MAPS = [
    "frida", "gum-js-loop", "gmain", "linjector", "gumjs",
    "re.frida.server", "frida-agent", "frida-gadget",
]

SG_FRIDA_THREADS = ["gmain", "gum-js", "frida", "pool-frida"]

SG_XPOSED_CLASSES = [
    "de.robv.android.xposed.XposedBridge",
    "de.robv.android.xposed.XposedHelpers",
    "de.robv.android.xposed.XC_MethodHook",
]

SG_XPOSED_PKGS = [
    "de.robv.android.xposed.installer",
    "org.meowcat.edxposed.manager",
    "org.lsposed.manager",
    "io.va.exposed",
]

# checkMagiskAdvanced ichidagi inline markerlar (alohida qiymat sifatida).
SG_INLINE = {
    "MK_MAGISK": "magisk",
    "MK_KSU": "KSU",
    "MK_DATA_ADB": "/data/adb",
}


def _emit_map(title, entries, key_w=70):
    print(f"// ===== {title} ({len(entries)} ta) =====")
    for k, v in entries:
        print(f'        Shield.dec("{enc(k)}") to')
        print(f'            Shield.dec("{enc(v)}"),   // {k} -> {v}')
    print()


def _emit_list(name, items):
    print(f"// ----- {name} ({len(items)} ta) -----")
    for s in items:
        print(f'        Shield.dec("{enc(s)}"),   // {s}')
    print()


def _emit_inline(d):
    print("// ----- inline markerlar (by lazy { Shield.dec(...) }) -----")
    for konst, plain in d.items():
        print(f'    private val {konst} by lazy {{ Shield.dec("{enc(plain)}") }}   // {plain}')
    print()


def emit_all():
    # Round-trip o'z-o'zini tekshiruvi — har bir literal ochilib, asl bilan
    # solishtiriladi. Mos kelmasa, xato bilan to'xtaymiz (buzilgan literal chiqmasin).
    everything = []
    for grp in (MALICIOUS_HASHES, MALICIOUS_PACKAGES, MALICIOUS_CERTS):
        for k, v in grp:
            everything += [k, v]
    for lst in (SG_SU_PATHS, SG_ROOT_APPS, SG_ROOT_CLOAKERS, SG_MAGISK_FILES,
                SG_FRIDA_MAPS, SG_FRIDA_THREADS, SG_XPOSED_CLASSES, SG_XPOSED_PKGS):
        everything += lst
    everything += list(SG_INLINE.values())
    for s in everything:
        assert dec(enc(s)) == s, f"ROUND-TRIP FAIL: {s!r}"
    print(f"# round-trip OK: {len(everything)} ta string\n")

    _emit_map("MaliciousHashes", MALICIOUS_HASHES)
    _emit_map("MaliciousPackages", MALICIOUS_PACKAGES)
    _emit_map("MaliciousCerts", MALICIOUS_CERTS)

    print("# ===== SecurityGuard =====")
    _emit_list("SU_PATHS", SG_SU_PATHS)
    _emit_list("ROOT_APPS", SG_ROOT_APPS)
    _emit_list("ROOT_CLOAKERS", SG_ROOT_CLOAKERS)
    _emit_list("MAGISK_FILES", SG_MAGISK_FILES)
    _emit_list("FRIDA_MAPS_MARKERS", SG_FRIDA_MAPS)
    _emit_list("FRIDA_THREAD_MARKERS", SG_FRIDA_THREADS)
    _emit_list("XPOSED_CLASSES", SG_XPOSED_CLASSES)
    _emit_list("XPOSED_PKGS", SG_XPOSED_PKGS)
    _emit_inline(SG_INLINE)


def main(argv):
    if len(argv) >= 2 and argv[1] not in ("--all", "-a"):
        for s in argv[1:]:
            print(f'{s!r:40} -> Shield.dec("{enc(s)}")')
        return
    emit_all()


if __name__ == "__main__":
    main(sys.argv)
