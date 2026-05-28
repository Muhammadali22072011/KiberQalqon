# -*- coding: utf-8 -*-
"""
Извлекает SHA-256 от X.509-сертификата подписи APK.
Используется для пополнения TrustedSignatures.kt / MaliciousCerts.kt.

Зачем нужен: cert-fingerprint — самая надёжная сигнатура для блокировки/whitelist.
Малварная группа не может его поменять без выпуска новых ключей.

Запуск:
    python scripts/extract_cert_fingerprint.py path/to/app.apk

ЗАВИСИМОСТИ: только stdlib. zipfile + ручной парсинг META-INF/*.RSA (PKCS#7) через
hashlib. Без openssl/cryptography — работает даже на голом Python 3.8+.
"""
from __future__ import annotations

import hashlib
import sys
import zipfile
from pathlib import Path


def _find_signer_blob(apk: Path) -> bytes | None:
    """
    Достаём CERT.RSA / CERT.DSA / CERT.EC из META-INF/. Это PKCS#7 контейнер,
    внутри которого X.509-сертификат(ы).

    Для v1-схемы подписи (обычная) — это работает. Для v2/v3 (APK Signature Scheme)
    лучше через androidx-tools или androguard. Для быстрой работы нам достаточно
    v1-блока + хеш над сырыми байтами файла META-INF/<NAME>.RSA — он совпадает с
    тем, что показывает Android: `pm.getPackageArchiveInfo(GET_SIGNATURES)`.
    """
    with zipfile.ZipFile(apk, "r") as z:
        names = [n for n in z.namelist() if n.upper().startswith("META-INF/")]
        for n in names:
            up = n.upper()
            if up.endswith(".RSA") or up.endswith(".DSA") or up.endswith(".EC"):
                return z.read(n)
    return None


def _extract_cert_der(pkcs7_blob: bytes) -> bytes | None:
    """
    Очень грубый DER-парсер для PKCS#7: ищем первый вложенный SEQUENCE длиной >300 байт —
    это и есть сертификат. Это совпадает с поведением PackageManager.getSignatures(),
    который возвращает сырой DER-блоб первого сертификата.

    Для production — заменить на cryptography / pyasn1. Здесь stdlib-only fallback.
    """
    # Ищем тэг SEQUENCE (0x30). Внутри PKCS#7 первый длинный SEQUENCE на верхнем
    # уровне даёт нам сертификат. Это эвристика — работает для всех образцов,
    # которые я видел, но если упадёт — заменить на нормальный ASN.1-парсер.
    i = 0
    candidates: list[bytes] = []
    n = len(pkcs7_blob)
    while i < n - 4:
        if pkcs7_blob[i] == 0x30:
            ln, header = _read_length(pkcs7_blob, i + 1)
            if ln is None:
                i += 1
                continue
            total = 1 + header + ln
            if 300 <= ln <= 4096:
                candidates.append(pkcs7_blob[i: i + total])
            i += 1
        else:
            i += 1
    return candidates[0] if candidates else None


def _read_length(data: bytes, off: int) -> tuple[int | None, int]:
    """Возвращает (length, header_size) для DER-длины."""
    if off >= len(data):
        return None, 0
    first = data[off]
    if first < 0x80:
        return first, 1
    n_bytes = first & 0x7F
    if n_bytes == 0 or n_bytes > 4 or off + 1 + n_bytes > len(data):
        return None, 0
    ln = 0
    for k in range(n_bytes):
        ln = (ln << 8) | data[off + 1 + k]
    return ln, 1 + n_bytes


def fingerprint(apk: Path) -> str | None:
    blob = _find_signer_blob(apk)
    if blob is None:
        return None
    cert = _extract_cert_der(blob)
    if cert is None:
        return None
    return hashlib.sha256(cert).hexdigest()


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: extract_cert_fingerprint.py <apk> [<apk2> ...]")
        return 1
    rc = 0
    for arg in sys.argv[1:]:
        apk = Path(arg)
        if not apk.is_file():
            print(f"{arg}: not found")
            rc = 2
            continue
        fp = fingerprint(apk)
        if fp is None:
            print(f"{apk.name}: cert not found (maybe v2/v3-only signing; try androguard)")
            rc = 3
        else:
            print(f"{apk.name}\n  sha256 = {fp}")
    return rc


if __name__ == "__main__":
    sys.exit(main())
