# @kiberqalqon_alerts_bot

Бесплатный Telegram-бот для приёма подозрительных APK от пользователей.

## Зачем

Пользователи получают вирусы через Telegram. Они могут **переслать APK сюда**, бот:
1. Скачает файл
2. Прогонит через `apk_analyzer.py` (наш существующий анализатор)
3. Ответит вердиктом на узбекском: 🟢 / 🟠 / 🔴
4. Сохранит файл в `samples/` для пополнения базы сигнатур

## Быстрый старт (локально)

```bash
pip install -r requirements.txt
export TELEGRAM_BOT_TOKEN=123456789:ABCdef...
python bot.py
```

## Бесплатный хостинг — варианты

### PythonAnywhere (рекомендую для старта)
- 100 МБ диска, всегда онлайн
- https://www.pythonanywhere.com — Free account
- Залить bot.py + установить deps в bash консоли
- "Always-on task" для polling — доступно даже на free

### Railway / Render
- 500 ч/мес бесплатно
- Подключить GitHub-репо → автодеплой
- Variables: `TELEGRAM_BOT_TOKEN=...`

### Oracle Cloud Always Free
- 24 ГБ RAM навсегда (Ampere ARM)
- VM Ubuntu 22 → `systemd` сервис

### Дома на старом ноуте
- Запустить как `nohup python bot.py &`
- Telegram polling работает через любой NAT без проброса портов

## Где взять токен

1. Открыть @BotFather в Telegram
2. `/newbot` → задать имя `KiberQalqon` и юзернейм `kiberqalqon_alerts_bot`
3. Получить токен вида `123456789:ABCdef...`
4. Положить в переменную `TELEGRAM_BOT_TOKEN`

## Что делать со собранными APK

В папке `samples/` накапливаются образцы. Раз в неделю:
1. Прогнать новые через `decode_apk.py` (если незашифрованные)
2. Извлечь cert SHA-256 через `scripts/extract_cert_fingerprint.py`
3. Добавить в `MaliciousCerts.kt` → выпустить новую версию KiberQalqon
4. Запушить новые сигнатуры в `MALWARE_SIGNATURES` (или через Firebase Remote Config)

## Лимиты

- Telegram Bot API: до **20 МБ** файлы. Больше — нужен MTProto (Telethon/Pyrogram).
- Не более 30 сообщений/сек одному чату — бот в reply ограничен этим.
- Бесплатные хостинги уходят в сон при бездействии → polling восстанавливает соединение.
