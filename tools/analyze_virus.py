# -*- coding: utf-8 -*-
"""
Полный конвейер анализа подозрительного APK:
  1) apk_analyzer.py  - распаковка + строковый скан + опасные разрешения
  2) unpack_apk.py    - глубокая распаковка всех APK в apk_unpacked/
  3) decode_apk.py    - расшифровка через apktool (манифест, ресурсы, smali)

Запуск: python analyze_virus.py

Все шаги выполняются последовательно; если какой-то падает — продолжаем со следующего,
чтобы получить максимум данных за один запуск.
"""
import subprocess
import sys
from pathlib import Path

# Windows cp1251 не печатает юникод — переключаем заранее.
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

BASE = Path(__file__).resolve().parent

STEPS = [
    ("Шаг 1/3: первичный анализ (apk_analyzer.py)", "apk_analyzer.py"),
    ("Шаг 2/3: глубокая распаковка (unpack_apk.py)", "unpack_apk.py"),
    ("Шаг 3/3: расшифровка через apktool (decode_apk.py)", "decode_apk.py"),
]


def run_step(title: str, script: str) -> int:
    path = BASE / script
    print()
    print("=" * 70)
    print(title)
    print("=" * 70)
    if not path.is_file():
        print(f"[!] Скрипт не найден: {script}")
        return 1
    try:
        # Стримим вывод подпроцесса напрямую (без capture) — пользователь видит прогресс.
        result = subprocess.run([sys.executable, str(path)], cwd=str(BASE))
        return result.returncode
    except KeyboardInterrupt:
        print("[!] Прервано пользователем")
        return 130
    except Exception as e:
        print(f"[!] Ошибка запуска {script}: {e}")
        return 1


def main() -> int:
    failed = []
    for title, script in STEPS:
        rc = run_step(title, script)
        if rc != 0:
            failed.append((script, rc))
            print(f"[!] {script} вернул код {rc} — продолжаю со следующим шагом")

    print()
    print("=" * 70)
    if not failed:
        print("Все шаги выполнены успешно.")
        return 0
    print(f"Завершено с ошибками в {len(failed)}/{len(STEPS)} шагах:")
    for script, rc in failed:
        print(f"  - {script}: exit {rc}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
