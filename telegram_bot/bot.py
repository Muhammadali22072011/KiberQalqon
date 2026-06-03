# -*- coding: utf-8 -*-
"""
@kiberqalqon_alerts_bot — приём подозрительных APK от пользователей.

ЦЕЛЬ: crowd-sourcing образцов малвари без платных API. Пользователь форвардит APK,
бот сохраняет, прогоняет через apk_analyzer.py, отвечает вердиктом и тегирует
файл в общем хранилище для последующего ручного разбора.

БЕСПЛАТНЫЙ ХОСТИНГ (есть из чего выбрать в 2026):
  - PythonAnywhere Free: 100 MB, всегда онлайн, идеально для бота с polling
  - Railway/Render Free Tier: 500 ч/мес, удобный CI/CD
  - Oracle Cloud Always Free: 24 GB RAM навсегда (но сложнее настроить)
  - Свой Raspberry Pi или старый ноут дома

ТРЕБОВАНИЯ:
  pip install python-telegram-bot==21.* aiofiles

ЗАПУСК:
  export TELEGRAM_BOT_TOKEN=...
  python bot.py

ARCHITECTURE:
  /start, /help     — приветствие, инструкция на узбекском
  document(*.apk)   — приём файла, скачивание, прогон анализа, ответ вердиктом
  callback queue    — если апк >50MB или анализ долгий: сразу подтверждаем, ответ позже
"""
from __future__ import annotations

import asyncio
import logging
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Optional

try:
    from telegram import Update
    from telegram.constants import ChatAction
    from telegram.ext import (
        ApplicationBuilder, CommandHandler, ContextTypes, MessageHandler, filters,
    )
except ImportError:
    print("python-telegram-bot o'rnatilmagan. O'rnatish: pip install python-telegram-bot==21.6")
    raise SystemExit(1)

BASE = Path(__file__).resolve().parent
# Корень проекта APK-анализа — там лежит apk_analyzer.py.
PROJECT_ROOT = BASE.parent
SAMPLES_DIR = BASE / "samples"
SAMPLES_DIR.mkdir(exist_ok=True)

# Лимит размера файла: бесплатные Telegram-боты получают до 20 MB через getFile.
# Большие файлы можно принимать только через MTProto (TelegramAPI), что сложнее.
MAX_APK_SIZE = 20 * 1024 * 1024

# apk_analyzer.py to'liq muvaffaqiyatli tahlilda doim shu bo'limni chiqaradi.
# Bu satr yo'q bo'lsa — tahlil yarim qolgan, natija ishonchsiz (hech qachon "safe" deb hisoblamaymiz).
_ANALYZER_SUCCESS_SENTINEL = "--- РЕКОМЕНДАЦИИ ---"

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
log = logging.getLogger("kiberqalqon-bot")


WELCOME_UZ = (
    "👋 Salom! Men *KiberQalqon* botiman.\n\n"
    "Telegram orqali kelgan shubhali .apk faylni menga yuboring — "
    "men uni avtomatik tekshiraman va xavf darajasini aytib beraman.\n\n"
    "⚠️ Faylni *o'rnatmang*, faqat menga forward qiling."
)
HELP_UZ = (
    "📋 *Qanday foydalanish:*\n"
    "1. Telegramdan kelgan APK faylni *forward* qiling\n"
    "2. Bot 10–30 soniyada javob beradi\n"
    "3. Yashil = xavfsiz, Sariq = shubhali, Qizil = XAVFLI\n\n"
    "❓ Savol bo'lsa: @kiberqalqon_help"
)


async def cmd_start(update: Update, _: ContextTypes.DEFAULT_TYPE) -> None:
    await update.message.reply_markdown(WELCOME_UZ)


async def cmd_help(update: Update, _: ContextTypes.DEFAULT_TYPE) -> None:
    await update.message.reply_markdown(HELP_UZ)


async def on_apk(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    msg = update.message
    doc = msg.document
    if doc is None:
        return

    # Принимаем только .apk и application/vnd.android.package-archive.
    name = (doc.file_name or "").lower()
    if not name.endswith(".apk") and doc.mime_type != "application/vnd.android.package-archive":
        await msg.reply_text("Bu APK fayl emas. Iltimos, .apk fayl yuboring.")
        return

    if doc.file_size and doc.file_size > MAX_APK_SIZE:
        await msg.reply_text(
            f"❌ Fayl juda katta ({doc.file_size / 1024 / 1024:.1f} MB).\n"
            f"Bot 20 MB gacha qabul qiladi."
        )
        return

    await msg.chat.send_action(ChatAction.TYPING)
    await msg.reply_text("⏳ Faylni qabul qildim, tekshirayapman...")

    save_path = SAMPLES_DIR / f"{int(time.time())}_{_safe_filename(doc.file_name or 'sample.apk')}"
    try:
        tg_file = await context.bot.get_file(doc.file_id)
        await tg_file.download_to_drive(custom_path=str(save_path))
    except Exception as e:
        log.exception("Failed to download")
        # Yarim yuklangan faylni darhol tozalaymiz, aks holda _run_analyzer corrupted ZIP'da yiqiladi
        try:
            if save_path.exists():
                save_path.unlink()
        except Exception:
            pass
        await msg.reply_text(f"❌ Faylni yuklab bo'lmadi: {e}")
        return

    # Yuklab bo'lingach o'lchamni tekshirib ko'ramiz — agar 100 bayt dan kichik bo'lsa
    # bu deyarli mumkin emas, demak yuklab olish to'liq tugamagan.
    if not save_path.exists() or save_path.stat().st_size < 100:
        try:
            save_path.unlink()
        except Exception:
            pass
        await msg.reply_text("❌ Yuklab olish tugamadi — fayl bo'sh yoki juda kichik.")
        return

    verdict = await asyncio.to_thread(_run_analyzer, save_path)
    text = _format_verdict(verdict, save_path)
    await msg.reply_markdown(text)


def _safe_filename(name: str) -> str:
    safe = "".join(c if c.isalnum() or c in "._-" else "_" for c in name)
    return safe[:120] or "sample.apk"


def _run_analyzer(apk_path: Path) -> dict:
    """
    Прогоняет наш apk_analyzer.py против файла. Возвращает {risk, reasons, perms}.
    Аналог CLI: копируем APK в проект, запускаем apk_analyzer.py, читаем result_analiza.txt.
    """
    workdir = SAMPLES_DIR / f"work_{apk_path.stem}"
    workdir.mkdir(exist_ok=True)
    target_apk = workdir / "sample.apk"
    try:
        target_apk.write_bytes(apk_path.read_bytes())
    except Exception as e:
        return {"risk": "error", "reasons": [f"nusxa olishda xato: {e}"], "perms": []}

    analyzer = PROJECT_ROOT / "apk_analyzer.py"
    if not analyzer.is_file():
        shutil.rmtree(workdir, ignore_errors=True)
        return {"risk": "error", "reasons": ["apk_analyzer.py topilmadi"], "perms": []}

    try:
        # APK yo'lini VA chiqish papkasini analizatorga ANIQ argument qilib beramiz —
        # u faylni cwd'dan emas, aniq berilgan yo'ldan oladi. (Avval cwd'ga tayanardi,
        # lekin analizator faylni o'z papkasidan qidirardi → har doim "topilmadi" → soxta SAFE.)
        proc = subprocess.run(
            [sys.executable, str(analyzer), str(target_apk), str(workdir)],
            cwd=str(workdir),
            timeout=120,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    except subprocess.TimeoutExpired:
        return {"risk": "error", "reasons": ["tahlil 2 daqiqadan oshib ketdi"], "perms": []}
    except Exception as e:
        return {"risk": "error", "reasons": [f"analizator ishlamadi: {e}"], "perms": []}
    finally:
        # work_* (nusxa sample.apk + apk_extracted) — vaqtinchalik, doim tozalaymiz.
        # To'plangan asl namuna (save_path) saqlanadi.
        shutil.rmtree(workdir, ignore_errors=True)

    out = proc.stdout or ""
    # POSITIV ISBOTSIZ "safe" YO'Q: exit kod 0 bo'lishi VA to'liq hisobot belgisi bo'lishi shart.
    # Aks holda (kod != 0, bo'sh/buzilgan/tushunarsiz chiqish) — error, hech qachon safe emas.
    if proc.returncode != 0:
        return {"risk": "error", "reasons": [f"analiz xato (kod {proc.returncode})"], "perms": []}
    if _ANALYZER_SUCCESS_SENTINEL not in out:
        return {"risk": "error", "reasons": ["tahlil to'liq tugamadi — natija ishonchsiz"], "perms": []}

    return _parse_analyzer_output(out)


def _parse_analyzer_output(text: str) -> dict:
    """Грубый парсинг текстового отчёта apk_analyzer.py."""
    perms: list[str] = []
    reasons: list[str] = []
    in_perms = False
    in_threats = False
    for line in text.splitlines():
        if "ОПАСНЫЕ" in line and "РАЗРЕШЕНИЯ" in line:
            in_perms, in_threats = True, False
            continue
        if "ПРИЗНАКИ ПОВЕДЕНИЯ" in line:
            in_perms, in_threats = False, True
            continue
        if line.startswith("---"):
            in_perms = in_threats = False
            continue
        s = line.strip()
        if in_perms and s.startswith("!"):
            perms.append(s.lstrip("! ").strip())
        elif in_threats and s.startswith("["):
            reasons.append(s)

    # Эвристика риска: те же правила что в ApkScanner.kt
    danger_perms = {
        "android.permission.SEND_SMS",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.BIND_ACCESSIBILITY_SERVICE",
    }
    score = sum(1 for p in perms if p in danger_perms)
    if reasons or score >= 2:
        risk = "danger"
    elif score >= 1 or perms:
        risk = "suspicious"
    else:
        risk = "safe"
    return {"risk": risk, "reasons": reasons[:5], "perms": perms[:8]}


def _format_verdict(v: dict, path: Path) -> str:
    risk = v.get("risk", "error")
    if risk == "error":
        body = "\n".join(v.get("reasons", []) or ["noma'lum xato"])
        return f"⚠️ *Tekshirib bo'lmadi*\n\nSabab:\n{body}"

    emoji = {"safe": "🟢 *XAVFSIZ*", "suspicious": "🟠 *SHUBHALI*", "danger": "🔴 *XAVFLI*"}[risk]
    parts = [emoji, f"`{path.name}`"]
    if v["perms"]:
        parts.append("\n*Talab qilingan ruxsatlar:*")
        for p in v["perms"]:
            short = p.rsplit(".", 1)[-1]
            parts.append(f"• {short}")
    if v["reasons"]:
        parts.append("\n*Xavf belgilari:*")
        for r in v["reasons"]:
            parts.append(f"• {r}")
    if risk == "danger":
        parts.append("\n❌ *Bu faylni telefonga o'rnatmang!*")
    return "\n".join(parts)


def main() -> int:
    token = os.environ.get("TELEGRAM_BOT_TOKEN")
    if not token:
        print("Xato: TELEGRAM_BOT_TOKEN o'zgaruvchisi berilmagan.", file=sys.stderr)
        return 1
    app = ApplicationBuilder().token(token).build()
    app.add_handler(CommandHandler("start", cmd_start))
    app.add_handler(CommandHandler("help", cmd_help))
    app.add_handler(MessageHandler(filters.Document.ALL, on_apk))
    log.info("Bot started — waiting for APKs...")
    app.run_polling(close_loop=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
