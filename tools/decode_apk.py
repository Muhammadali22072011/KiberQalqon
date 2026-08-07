# -*- coding: utf-8 -*-
"""
Попытка полностью расшифровать APK: манифест в XML, ресурсы, smali/код.
Ищет apktool в PATH или в папках: apktool/, C:\\apktool.
При отсутствии Java распаковывает jdk21.zip / adoptium-jdk21.zip в папку jdk.
"""
import os
import subprocess
import sys
import tempfile
import unicodedata
import zipfile
import shutil
from pathlib import Path

# Windows cp1251 не печатает юникод-имена APK (U+3164 hangul filler и т.п.).
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

BASE = Path(__file__).resolve().parent
ROOT = BASE.parent
APK_DIR = ROOT / "malware" / "samples"
DECODED = ROOT / "malware" / "unpacked" / "apk_decoded"
JDK_DIR = BASE / "jdk"

# Не считаем кандидатами наши собственные сборки.
SELF_PREFIXES = ("kiberqalqon", "apkguard")


def _jdk_usable(jdk_dir: Path) -> bool:
    """java.exe одного мало: полураспакованный JDK без jvm.dll падает с
    "missing `server' JVM". Требуем и саму VM-библиотеку."""
    if not (jdk_dir / "bin" / "java.exe").is_file():
        return False
    return any(
        (jdk_dir / sub / "server" / "jvm.dll").is_file() for sub in ("bin", "lib")
    )


def ensure_jdk():
    """Если папки jdk нет, распаковать JDK из zip (adoptium-jdk21.zip или jdk21.zip)."""
    if _jdk_usable(JDK_DIR):
        return
    for name in ("adoptium-jdk21.zip", "jdk21.zip", "openjdk-21.zip"):
        zpath = BASE / name
        if not zpath.is_file():
            continue
        print("Распаковка JDK из", name, "...")
        extract_to = BASE / "jdk_extract"
        extract_to.mkdir(exist_ok=True)
        try:
            with zipfile.ZipFile(zpath, "r") as z:
                z.extractall(extract_to)
        except zipfile.BadZipFile:
            print("  Файл не zip или повреждён, пробую следующий.")
            if extract_to.exists():
                shutil.rmtree(extract_to, ignore_errors=True)
            continue
        try:
            subs = list(extract_to.iterdir())
            if len(subs) == 1 and subs[0].is_dir():
                if JDK_DIR.exists():
                    shutil.rmtree(JDK_DIR)
                subs[0].rename(JDK_DIR)
            else:
                JDK_DIR.mkdir(parents=True, exist_ok=True)
                for s in subs:
                    shutil.move(str(s), str(JDK_DIR / s.name))
            print("JDK распакован в папку jdk")
        finally:
            if extract_to.exists():
                shutil.rmtree(extract_to, ignore_errors=True)
        return


def find_system_java_home():
    """Ищем установленный JDK в стандартных папках Windows."""
    if _jdk_usable(JDK_DIR):
        return str(JDK_DIR)
    roots = [
        Path(os.environ.get("ProgramFiles", "C:\\Program Files")),
        Path(os.environ.get("ProgramFiles(x86)", "C:\\Program Files (x86)")),
    ]
    for root in roots:
        if not root.is_dir():
            continue
        for folder in ("Java", "Eclipse Adoptium", "Microsoft", "AdoptOpenJDK"):
            p = root / folder
            if not p.is_dir():
                continue
            for sub in p.iterdir():
                if sub.is_dir() and (sub / "bin" / "java.exe").is_file():
                    return str(sub)
    return None


def find_apktool():
    """Путь к apktool.bat или apktool (для вызова subprocess)."""
    for name in ("apktool.bat", "apktool"):
        p = BASE / name
        if p.is_file():
            return str(p)
    p = BASE / "apktool" / "apktool.bat"
    if p.is_file():
        return str(p)
    for folder in (
        Path(os.environ.get("LOCALAPPDATA", "")) / "apktool",
        Path(os.environ.get("USERPROFILE", "")) / "apktool",
        Path("C:/apktool"),
        Path("C:/Tools/apktool"),
    ):
        if folder:
            for name in ("apktool.bat", "apktool"):
                f = folder / name
                if f.is_file():
                    return str(f)
    return None


def _has_non_ascii(name: str) -> bool:
    """Имя содержит non-ASCII или невидимые юникод-пробелы — apktool может его потерять."""
    # Нормализуем в NFC, чтобы декомпозированные символы (e + ́) распознавались корректно.
    nfc = unicodedata.normalize("NFC", name)
    return any(ord(c) > 127 for c in nfc)


def _run_apktool(apktool_exe, apk_path: Path, env) -> tuple[bool, str]:
    """Запустить apktool, не накапливая весь stdout в памяти (важно на больших APK)."""
    if apktool_exe:
        cmd = [apktool_exe, "d", str(apk_path), "-o", str(DECODED), "-f"]
    else:
        cmd = ["apktool", "d", str(apk_path), "-o", str(DECODED), "-f"]
    print("Запуск:", " ".join(cmd))

    # Стримим stdout/stderr построчно вместо capture_output (иначе большой APK может съесть всю RAM).
    # Последние 100 строк храним на случай, если надо показать причину сбоя.
    tail = []
    try:
        proc = subprocess.Popen(
            cmd, cwd=BASE, env=env,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace",
        )
    except FileNotFoundError:
        raise
    assert proc.stdout is not None
    for line in proc.stdout:
        line = line.rstrip()
        tail.append(line)
        if len(tail) > 100:
            tail.pop(0)
    rc = proc.wait()
    return rc == 0, "\n".join(tail)


def main():
    apks = sorted(p for p in APK_DIR.glob("*.apk") if not p.name.lower().startswith(SELF_PREFIXES))
    if not apks:
        print("Нет подозрительных .apk в", APK_DIR)
        return 0
    ensure_jdk()
    java_home = find_system_java_home()
    env = os.environ.copy()
    if java_home:
        env["JAVA_HOME"] = java_home
        print("Используется Java:", java_home)
    apktool_exe = find_apktool()

    for apk in apks:
        # Сбрасываем ошибку перед каждым APK — иначе при провале одного
        # покажется чужая ошибка как итоговая.
        last_error = None
        apk_path = apk
        temp_apk = None

        # Если имя содержит non-ASCII (юникод-пробелы и т.п.) — копируем в безопасный путь.
        if _has_non_ascii(apk.name):
            tf = tempfile.NamedTemporaryFile(
                suffix=".apk", prefix="apkdecode_", dir=str(BASE), delete=False
            )
            tf.close()
            temp_apk = Path(tf.name)
            try:
                shutil.copy2(apk, temp_apk)
                apk_path = temp_apk
                print(f"Файл с юникод-именем скопирован в {temp_apk.name}")
            except Exception as e:
                print("  Не удалось скопировать:", e)
                if temp_apk.exists():
                    temp_apk.unlink(missing_ok=True)
                continue

        try:
            ok, output = _run_apktool(apktool_exe, apk_path, env)
            if ok:
                print("Готово. Расшифровано в:", DECODED)
                print("  APK:", apk.name)
                print("  AndroidManifest.xml — читаемый XML")
                print("  res/ — расшифрованные ресурсы")
                print("  smali*/ — код (smali)")
                return 0
            last_error = output or "(нет вывода)"
            if "encrypted" in last_error.lower() or "ZipException" in last_error:
                print("  Этот APK с паролем/шифрованием — apktool не может. Пробую следующий .apk ...")
            else:
                print("  Ошибка:", last_error[-500:])
        except FileNotFoundError:
            print("apktool не найден. Положи apktool.bat и apktool.jar в папку проекта.")
            print("Инструкция: USTANOVKA_APKTOOL_JADX.txt")
            return 1
        finally:
            # ВАЖНО: чистим временный файл всегда, даже при exception/keyboard interrupt.
            if temp_apk is not None and temp_apk.exists():
                try:
                    temp_apk.unlink()
                except Exception:
                    pass

    print()
    print("Ни один APK не удалось расшифровать.")
    if last_error and ("encrypted" in last_error.lower() or "ZipException" in last_error):
        print("Причина: внутри APK часть файлов (classes.dex и др.) зашифрована паролем ZIP.")
        print("Apktool без пароля их не открывает. Пароль может быть зашит в приложении —")
        print("без полной распаковки его не узнать. Можно попробовать JADX: открыть APK в jadx-gui —")
        print("иногда он умеет читать часть кода.")
    elif last_error:
        print("Последняя ошибка:", last_error[-400:])
    return 1


if __name__ == "__main__":
    sys.exit(main() or 0)
