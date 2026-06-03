# -*- coding: utf-8 -*-
"""
Скрипт для первичного анализа APK (в т.ч. подозрительного).
Извлекает содержимое, показывает структуру и опасные признаки.
Запуск: python apk_analyzer.py

ОСТОРОЖНО: Анализируемый APK может быть трояном (кража карт, рассылка
по контактам, сокрытие номера, закрепление в системе). Скрипт только
ЧИТАЕТ и РАСПАКОВЫВАЕТ файлы — не устанавливает и не запускает код.
"""
import zipfile
import os
import sys
from pathlib import Path

# Папка с APK и куда распаковать
BASE = Path(__file__).resolve().parent
APK_DIR = BASE
OUT_DIR = BASE / "apk_extracted"

# Не считаем кандидатами на анализ — это наш собственный антивирус и его сборки.
SELF_PREFIXES = ("kiberqalqon", "kiberqalqon")

# Защита от zip-bomb: суммарный распакованный размер и одиночный файл.
MAX_TOTAL_UNPACKED = 500 * 1024 * 1024  # 500 MB суммарно
MAX_SINGLE_FILE = 100 * 1024 * 1024     # 100 MB на файл

# Опасные разрешения Android (Dangerous / Signature-level)
DANGEROUS_PERMISSIONS = {
    "android.permission.SEND_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.READ_SMS",
    "android.permission.RECORD_AUDIO",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.CAMERA",
    "android.permission.READ_CONTACTS",
    "android.permission.WRITE_CONTACTS",
    "android.permission.READ_CALL_LOG",
    "android.permission.CALL_PHONE",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
    "android.permission.PACKAGE_USAGE_STATS",
    "android.permission.READ_HISTORY_BOOKMARKS",
    "android.permission.GET_ACCOUNTS",
    "android.permission.READ_PHONE_STATE",
}


def find_apk():
    """Найти первый подозрительный .apk в папке (исключая наш собственный)."""
    candidates = []
    for f in APK_DIR.iterdir():
        if f.suffix.lower() != ".apk":
            continue
        if f.name.lower().startswith(SELF_PREFIXES):
            continue
        candidates.append(f)
    if not candidates:
        return None
    # Самый свежий — обычно тот, что только что прислали на анализ.
    candidates.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    return candidates[0]


_WIN_FORBIDDEN_CHARS = '<>:"|?*'


def _sanitize(name: str) -> str:
    """Заменяем Windows-запрещённые символы — вирусные APK часто содержат `styles.xml"` и т.п."""
    parts = []
    for part in name.replace("\\", "/").split("/"):
        cleaned = "".join(("_" if c in _WIN_FORBIDDEN_CHARS or ord(c) < 32 else c) for c in part)
        if cleaned.upper().split(".")[0] in {
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        }:
            cleaned = "_" + cleaned
        cleaned = cleaned.rstrip(" .") or "_"
        parts.append(cleaned)
    return "/".join(p for p in parts if p)


def _safe_target(out_dir: Path, name: str):
    """Безопасный путь внутри out_dir (защита от path traversal через имена в ZIP) + санитизация."""
    base = out_dir.resolve()
    safe_name = _sanitize(name)
    if not safe_name:
        return None
    candidate = (out_dir / safe_name).resolve()
    try:
        candidate.relative_to(base)
    except ValueError:
        return None
    return candidate


def extract_apk(apk_path):
    """Распаковать APK как ZIP с защитой от path-traversal и zip-bomb."""
    OUT_DIR.mkdir(exist_ok=True)
    skipped = 0
    total_written = 0
    with zipfile.ZipFile(apk_path, "r") as z:
        for info in z.infolist():
            if info.file_size > MAX_SINGLE_FILE:
                print(f"  [skip] слишком большой файл: {info.filename} ({info.file_size} bytes)")
                skipped += 1
                continue
            if total_written + info.file_size > MAX_TOTAL_UNPACKED:
                print(f"  [stop] превышен суммарный лимит распаковки ({MAX_TOTAL_UNPACKED} bytes)")
                break

            target = _safe_target(OUT_DIR, info.filename)
            if target is None:
                print(f"  [skip] path traversal: {info.filename}")
                skipped += 1
                continue

            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue

            target.parent.mkdir(parents=True, exist_ok=True)
            try:
                with z.open(info) as src, open(target, "wb") as dst:
                    remaining = info.file_size
                    while remaining > 0:
                        chunk = src.read(min(65536, remaining))
                        if not chunk:
                            break
                        dst.write(chunk)
                        remaining -= len(chunk)
                        total_written += len(chunk)
            except Exception as e:
                print(f"  [skip] {info.filename}: {e}")
                skipped += 1
    print(f"[+] Распаковано в: {OUT_DIR} (пропущено: {skipped})")


def list_structure():
    """Показать структуру распакованного APK."""
    if not OUT_DIR.exists():
        print("[-] Сначала распакуйте APK.")
        return
    print("\n--- СТРУКТУРА APK ---")
    for root, dirs, files in os.walk(OUT_DIR):
        rel = os.path.relpath(root, OUT_DIR)
        if rel == ".":
            rel = "/"
        for f in files:
            path = os.path.join(rel, f)
            size = os.path.getsize(os.path.join(root, f))
            print(f"  {path}  ({size} bytes)")


def read_manifest_permissions(apk_path: Path | None = None):
    """
    AndroidManifest.xml в APK хранится в бинарном формате (AXML).

    Стратегия: сначала пробуем androguard (правильный AXML-парсер), если есть.
    Иначе fallback — поиск строк в байтах. Грубо, но работает без зависимостей.
    """
    if apk_path is not None:
        perms = _read_perms_via_androguard(apk_path)
        if perms is not None:
            return perms

    manifest_path = OUT_DIR / "AndroidManifest.xml"
    if not manifest_path.exists():
        print("[-] AndroidManifest.xml не найден в распакованном APK.")
        return []
    data = manifest_path.read_bytes()
    found = set()
    text_utf16 = data.decode("utf-16-le", errors="ignore")
    text_utf8 = data.decode("utf-8", errors="ignore")
    for perm in DANGEROUS_PERMISSIONS:
        if perm in text_utf16 or perm in text_utf8:
            found.add(perm)
    return sorted(found)


def _read_perms_via_androguard(apk_path: Path):
    """
    Возвращает список опасных разрешений через androguard или None, если он не установлен.
    Не падает: любое исключение → возврат None и работаем через fallback.

    androguard — единственная серьёзная Python-библа для AXML/DEX. Опциональна:
        pip install androguard==3.4.0    # стабильная, минимум зависимостей
        pip install androguard==4.*       # современная, лучше работает с новыми APK
    """
    try:
        # androguard 3.x публичный API
        from androguard.core.bytecodes.apk import APK  # type: ignore
    except Exception:
        try:
            # androguard 4.x новое расположение
            from androguard.core.apk import APK  # type: ignore
        except Exception:
            return None

    try:
        apk = APK(str(apk_path))
        all_perms = set(apk.get_permissions())
        return sorted(p for p in all_perms if p in DANGEROUS_PERMISSIONS)
    except Exception as e:
        print(f"  [androguard] парсинг провалился: {e} — fallback на строковый поиск")
        return None


# Строки в коде/ресурсах, указывающие на описанное поведение вируса
THREAT_STRINGS = {
    "карты/деньги": ["card", "cvv", "transfer", "payment", "bank", "wallet", "balance", "otp"],
    "контакты/рассылка": ["contact", "contacts", "sendSms", "send_sms", "phonebook"],
    "удаление/сокрытие": ["deleteSms", "delete_sms", "clearLog"],
    "закрепление в системе": ["BootCompleted", "boot_completed", "DeviceAdmin", "startForeground"],
}


def scan_for_threat_strings():
    """Сканировать распакованные файлы на подозрительные строки."""
    if not OUT_DIR.exists():
        return
    print("\n--- ПРИЗНАКИ ПОВЕДЕНИЯ (карты, контакты, рассылка, закрепление) ---")
    found_any = False
    for root, dirs, files in os.walk(OUT_DIR):
        for f in files:
            path = Path(root) / f
            # Пропускаем огромные файлы (libs, ресурсы) — на них поиск создаёт много шума и долго.
            try:
                if path.stat().st_size > 8 * 1024 * 1024:
                    continue
            except OSError:
                continue
            try:
                data = path.read_bytes()
            except Exception:
                continue

            # Декодируем оба варианта по отдельности — не склеиваем, чтобы не удваивать RAM.
            text_lower_utf8 = data.decode("utf-8", errors="ignore").lower()
            text_lower_utf16 = data.decode("utf-16-le", errors="ignore").lower()
            rel = path.relative_to(OUT_DIR)
            matched_categories = set()
            for category, keywords in THREAT_STRINGS.items():
                if category in matched_categories:
                    continue
                for kw in keywords:
                    kw_lower = kw.lower()
                    if kw_lower in text_lower_utf8 or kw_lower in text_lower_utf16:
                        found_any = True
                        matched_categories.add(category)
                        print(f"  [{category}] '{kw}' в {rel}")
                        break  # эту категорию для этого файла больше не печатаем
    if not found_any:
        print("  (Подозрительные строки не найдены в бинарниках; полный разбор — в JADX по коду.)")


def analyze_dex():
    """Проверить наличие .dex файлов (код приложения)."""
    if not OUT_DIR.exists():
        return
    dex_files = list(OUT_DIR.glob("*.dex"))
    print(f"\n--- DEX файлы (код приложения): {len(dex_files)} ---")
    for d in dex_files:
        print(f"  {d.name}  ({d.stat().st_size} bytes)")


class _Tee:
    """Раздвоитель stdout — пишет и в реальный поток, и в буфер для финального отчёта."""

    def __init__(self, buf):
        self._buf = buf
        self._real = sys.__stdout__
        # На Windows консоль по умолчанию cp1251 — печать юникод-имени файла (U+3164 и т.п.)
        # упадёт с UnicodeEncodeError. Переключаем поток в UTF-8 + replace, если можем.
        try:
            self._real.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass

    def write(self, s):
        self._buf.write(s)
        try:
            self._real.write(s)
        except UnicodeEncodeError:
            enc = getattr(self._real, "encoding", "ascii") or "ascii"
            self._real.write(s.encode(enc, errors="replace").decode(enc, errors="replace"))

    def flush(self):
        self._buf.flush()
        try:
            self._real.flush()
        except Exception:
            pass


def main():
    # Аргументы (для вызова из бота): argv[1] = путь к APK, argv[2] = выходная папка.
    # Без аргументов — старое поведение: ищем APK в папке скрипта (standalone-режим).
    global OUT_DIR
    args = sys.argv[1:]
    apk_arg = args[0] if len(args) >= 1 else None
    out_base = Path(args[1]).resolve() if len(args) >= 2 else BASE
    # Выходную папку делаем per-invocation, чтобы параллельные запуски бота не затирали
    # друг другу apk_extracted / result_analiza.txt.
    OUT_DIR = out_base / "apk_extracted"

    from io import StringIO
    buf = StringIO()
    # Подмена stdout ДО любых print() — иначе сообщение "APK не найден" не попадёт в отчёт.
    old_stdout = sys.stdout
    sys.stdout = _Tee(buf)
    try:
        print("=== Анализатор APK ===\n")
        if apk_arg:
            apk = Path(apk_arg)
            if not apk.is_file():
                print(f"Указанный APK не найден: {apk_arg}")
                return 1
        else:
            apk = find_apk()
            if not apk:
                print("В папке не найден подозрительный .apk файл (исключая KiberQalqon*.apk).")
                return 1
        print(f"APK: {apk.name}\n")
        extract_apk(apk)
        list_structure()
        analyze_dex()
        # Передаём apk напрямую — если androguard установлен, спарсит manifest
        # без оглядки на распакованный AXML (надёжнее).
        perms = read_manifest_permissions(apk)
        scan_for_threat_strings()
        print("\n--- ОПАСНЫЕ / ПОДОЗРИТЕЛЬНЫЕ РАЗРЕШЕНИЯ ---")
        if perms:
            for p in perms:
                print(f"  ! {p}")
        else:
            print("  (Не удалось извлечь из бинарного манифеста. Используйте apktool для полного разбора.)")
        print("\n--- ЧТО ОЗНАЧАЮТ ПРИЗНАКИ ---")
        print("  - Кража карт/деньги: SEND_SMS, READ_SMS, INTERNET + строки bank/card/otp в коде.")
        print("  - Рассылка всем контактам: READ_CONTACTS + sendSms/contacts в коде.")
        print("  - Удаление номера/следов: deleteSms/clearLog в коде.")
        print("  - Заседает в системе: RECEIVE_BOOT_COMPLETED, сервисы, DeviceAdmin.")
        print("\n--- РЕКОМЕНДАЦИИ ---")
        print(f"  1. Для полного разбора манифеста: apktool d \"{apk.name}\"")
        print(f"  2. Для просмотра Java-кода: jadx-gui или jadx \"{apk.name}\"")
        print("  3. Результаты распаковки в папке: apk_extracted")
        return 0
    finally:
        sys.stdout = old_stdout
        report_path = out_base / "result_analiza.txt"
        try:
            report_path.write_text(buf.getvalue(), encoding="utf-8")
            print(f"\n[+] Отчёт сохранён: {report_path}")
        except Exception as e:
            print(f"[!] Не удалось сохранить отчёт: {e}")


if __name__ == "__main__":
    sys.exit(main() or 0)
