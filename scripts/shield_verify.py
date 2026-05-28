#!/usr/bin/env python3
"""
shield_verify.py — .kt fayllardagi Shield.dec("...") literallarini o'qib,
deshifrlab, etalon plaintext bilan solishtiradi. Transkripsiya xatosini
(uzun hex'da bitta belgi adashsa) build'siz, shu yerda tutadi.

Ishlatish:  python scripts/shield_verify.py
Chiqish kodi 0 = hammasi to'g'ri, 1 = nomuvofiqlik bor.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shield_encode as se  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KT = os.path.join(ROOT, "ApkGuard", "app", "src", "main", "java", "com", "kiberqalqon")

# Shield literallar — har doim lowercase, juft uzunlikdagi hex, qo'shtirnoq ichida.
# Bu Shield.dec("...") VA Shield.decList("...", ...) ikkalasini ham tutadi.
# EXPECTED_RELEASE_SIGNATURE_SHA256 (uppercase) va boot-prop "0"/"1" (qisqa, toq)
# filtrlanadi: lowercase + len>=4 + juft uzunlik.
DEC_RE = re.compile(r'"([0-9a-f]{4,})"')


def literals(path):
    with open(path, encoding="utf-8") as f:
        text = f.read()
    return [se.dec(h) for h in DEC_RE.findall(text) if len(h) % 2 == 0]


def check_map(filename, canonical):
    """IOC bazasi: literallar tartibda [k1,v1,k2,v2,...] — juftlab, dict solishtirish."""
    decoded = literals(os.path.join(KT, filename))
    if len(decoded) != 2 * len(canonical):
        return [f"{filename}: literal soni {len(decoded)}, kutilgan {2*len(canonical)}"]
    got = {decoded[i]: decoded[i + 1] for i in range(0, len(decoded), 2)}
    want = dict(canonical)
    errs = []
    for k, v in want.items():
        if k not in got:
            errs.append(f"{filename}: kalit yo'q/buzilgan: {k!r}")
        elif got[k] != v:
            errs.append(f"{filename}: {k!r} -> {got[k]!r}, kutilgan {v!r}")
    return errs


def check_set(filename, expected_list):
    """SecurityGuard: barcha literallar multiset sifatida etalonga teng bo'lsin."""
    decoded = sorted(literals(os.path.join(KT, filename)))
    want = sorted(expected_list)
    if decoded == want:
        return []
    errs = []
    miss = sorted(set(want) - set(decoded))
    extra = sorted(set(decoded) - set(want))
    for m in miss:
        errs.append(f"{filename}: yetishmaydi/buzilgan: {m!r}")
    for e in extra:
        errs.append(f"{filename}: kutilmagan (buzilgan?): {e!r}")
    if not errs:  # bir xil to'plam, lekin dublikat soni farq qiladi
        errs.append(f"{filename}: literal soni {len(decoded)}, kutilgan {len(want)}")
    return errs


def main():
    all_errs = []
    all_errs += check_map("MaliciousHashes.kt", se.MALICIOUS_HASHES)
    all_errs += check_map("MaliciousPackages.kt", se.MALICIOUS_PACKAGES)
    all_errs += check_map("MaliciousCerts.kt", se.MALICIOUS_CERTS)

    sg_expected = (
        se.SG_SU_PATHS + se.SG_ROOT_APPS + se.SG_ROOT_CLOAKERS + se.SG_MAGISK_FILES +
        se.SG_FRIDA_MAPS + se.SG_FRIDA_THREADS + se.SG_XPOSED_CLASSES + se.SG_XPOSED_PKGS +
        list(se.SG_INLINE.values())
    )
    all_errs += check_set("SecurityGuard.kt", sg_expected)

    if all_errs:
        print("XATO — nomuvofiqlik topildi:")
        for e in all_errs:
            print("  -", e)
        sys.exit(1)
    print("OK — barcha Shield.dec literallari etalonga mos (IOC bazalari + SecurityGuard).")


if __name__ == "__main__":
    main()
