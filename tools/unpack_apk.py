# -*- coding: utf-8 -*-
"""Полная распаковка ВСЕХ APK в apk_unpacked/<имя_apk>/. Запуск: python unpack_apk.py"""
import sys
import zlib
import zipfile
from pathlib import Path

# Windows-консоль по умолчанию cp1251 — не печатает юникод-имена APK (U+3164 и т.п.).
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

BASE = Path(__file__).resolve().parent
ROOT = BASE.parent
APK_DIR = ROOT / "malware" / "samples"
OUT = ROOT / "malware" / "unpacked" / "apk_unpacked"
REPORTS = ROOT / "reports"

# Не считаем кандидатами наши собственные сборки.
SELF_PREFIXES = ("kiberqalqon", "kiberqalqon")

# Защита от zip-bomb.
MAX_SINGLE_FILE = 100 * 1024 * 1024
MAX_TOTAL = 500 * 1024 * 1024

# Частые пароли для защищённых записей в ZIP. Пустой пароль не нужен —
# zipfile.extract() сначала пробует без пароля автоматически.
COMMON_PASSWORDS = [
    b" ", b"0", b"1234", b"12345", b"123456", b"password", b"android",
    b"secret", b"qwerty", b"admin", b"0000", b"1111", b"apk", b"gradle",
]


_WIN_FORBIDDEN_CHARS = '<>:"|?*'


def _sanitize(name: str) -> str:
    """
    Заменяем Windows-запрещённые символы в каждом сегменте.
    Вредоносные APK иногда специально содержат имена вроде `styles.xml"` или `con.txt`,
    чтобы сломать антивирусные распаковщики.
    """
    parts = []
    for part in name.replace("\\", "/").split("/"):
        cleaned = "".join(("_" if c in _WIN_FORBIDDEN_CHARS or ord(c) < 32 else c) for c in part)
        # Зарезервированные DOS-имена.
        if cleaned.upper().split(".")[0] in {
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        }:
            cleaned = "_" + cleaned
        # Имена не должны заканчиваться на пробел или точку.
        cleaned = cleaned.rstrip(" .") or "_"
        parts.append(cleaned)
    return "/".join(p for p in parts if p)


def safe_target(parent: Path, name: str):
    """Путь внутри parent, без выхода наружу (защита от path traversal в ZIP) + санитизация имён."""
    base = parent.resolve()
    safe_name = _sanitize(name)
    if not safe_name:
        return None
    candidate = (parent / safe_name).resolve()
    try:
        candidate.relative_to(base)
    except ValueError:
        return None
    return candidate


def extract_one(z: zipfile.ZipFile, info: zipfile.ZipInfo, out_path: Path, written_so_far: int) -> tuple[bool, int]:
    """
    Извлечь один файл; при шифровании — перебор паролей.
    Возвращает (успех, сколько байт записано).
    """
    # Защита от path traversal и подозрительных путей.
    if ".." in info.filename or info.filename.count("/") > 20 or "///" in info.filename:
        return False, 0
    if info.file_size > MAX_SINGLE_FILE:
        return False, 0
    target = safe_target(out_path, info.filename)
    if target is None:
        return False, 0

    if info.is_dir():
        target.mkdir(parents=True, exist_ok=True)
        return True, 0

    target.parent.mkdir(parents=True, exist_ok=True)

    def _write(stream) -> int:
        with open(target, "wb") as dst:
            total = 0
            while True:
                chunk = stream.read(65536)
                if not chunk:
                    break
                dst.write(chunk)
                total += len(chunk)
                if written_so_far + total > MAX_TOTAL:
                    raise RuntimeError("total unpack limit exceeded")
            return total

    # Попытка без пароля
    try:
        with z.open(info) as src:
            return True, _write(src)
    except RuntimeError as e:
        msg = str(e).lower()
        if "encrypted" not in msg and "password" not in msg and "limit" not in msg:
            raise
        if "limit" in msg:
            return False, 0
    except (zipfile.BadZipFile, zlib.error):
        pass

    # Перебор паролей
    for pwd in COMMON_PASSWORDS:
        try:
            with z.open(info, pwd=pwd) as src:
                return True, _write(src)
        except (RuntimeError, TypeError, zipfile.BadZipFile, zlib.error):
            continue
    return False, 0


def unpack(apk: Path) -> None:
    """Распаковать один APK в OUT/<имя>/."""
    target_dir = OUT / apk.stem
    target_dir.mkdir(parents=True, exist_ok=True)
    skipped = []
    written = 0
    try:
        with zipfile.ZipFile(apk, "r") as z:
            for info in z.infolist():
                ok, n = extract_one(z, info, target_dir, written)
                if ok:
                    written += n
                else:
                    skipped.append(info.filename)
    except zipfile.BadZipFile as e:
        print(f"  [!] {apk.name}: повреждённый ZIP — {e}")
        return

    files = [str(f.relative_to(target_dir)) for f in target_dir.rglob("*") if f.is_file()]
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / f"unpacked_files_{apk.stem}.txt").write_text(
        "\n".join(sorted(files)), encoding="utf-8"
    )
    print(f"  Распаковано: {apk.name} -> {target_dir}  (файлов: {len(files)})")
    if skipped:
        print(f"    Пропущено: {len(skipped)} (нужен пароль/слишком большие/traversal)")


def main():
    apks = [p for p in APK_DIR.glob("*.apk") if not p.name.lower().startswith(SELF_PREFIXES)]
    if not apks:
        print("Подозрительные APK не найдены в", APK_DIR)
        return
    OUT.mkdir(parents=True, exist_ok=True)
    for apk in apks:
        unpack(apk)
    print()
    print("Готово. Корневая папка:", OUT)


if __name__ == "__main__":
    main()
