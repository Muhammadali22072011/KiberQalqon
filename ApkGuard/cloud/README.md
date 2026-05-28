# KiberQalqon Cloud — Vercel + Supabase + Telegram

Android ilovasi skan natijalarini yuboradigan, Supabase bazasida saqlaydigan
va Telegram orqali xabar beradigan serverless backend.

```
📱 Android  →  ☁️ Vercel API  →  🗄️ Supabase
                     ↓
                💬 Telegram group
```

---

## Sozlash — 4 qadam

### 1) Supabase loyihasi (5 daqiqa)

1. <https://supabase.com> ga kirib **New project** bos
2. Region: `Frankfurt (eu-central-1)` (Toshkentga eng yaqini)
3. Database password — saqlab qo'y
4. Proyekt ochilganda → **SQL Editor → New query**
5. `supabase/schema.sql` faylini to'liq ko'chir va **Run**
6. **Settings → API** sahifasidan ikkita kalitni nusxala:
   - `Project URL` → `SUPABASE_URL`
   - `service_role` (secret!) → `SUPABASE_SERVICE_KEY`

### 2) Telegram bot (2 daqiqa)

1. Telegram'da [@BotFather](https://t.me/BotFather) ga `/newbot` yoz
2. Nomi: `KiberQalqon Alerts`, username: `kiberqalqon_xxx_bot`
3. BotFather token beradi → `TELEGRAM_BOT_TOKEN`
4. Yangi *guruh* yarat, botni admin qil
5. Guruhga [@userinfobot](https://t.me/userinfobot) qo'sh, u **chat_id** ni ko'rsatadi (manfiy son: `-1001234567890`) → `ADMIN_CHAT_IDS`
6. `TELEGRAM_WEBHOOK_SECRET` va `DEVICE_SHARED_SECRET` uchun random 32-belgi yarat (Linux/Mac: `openssl rand -hex 32`, Windows PS: `[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')`)

### 3) Vercel deploy (3 daqiqa)

```powershell
cd "C:\Users\Muhammadali\Desktop\APK Virus Analysis\ApkGuard\cloud"
npm install
npx vercel login
npx vercel link        # yangi loyiha yaratasan
```

Keyin **Vercel Dashboard → Project → Settings → Environment Variables** ga
`.env.example` dagi *barcha* qiymatlarni qo'sh:

| Variable | Qayerdan |
|----------|----------|
| `SUPABASE_URL` | Supabase Settings → API |
| `SUPABASE_SERVICE_KEY` | Supabase Settings → API (service_role) |
| `TELEGRAM_BOT_TOKEN` | @BotFather |
| `TELEGRAM_WEBHOOK_SECRET` | random 32 hex |
| `DEVICE_SHARED_SECRET` | random 32 hex |
| `ADMIN_CHAT_IDS` | guruh chat_id (manfiy son) |

Endi deploy:

```powershell
npx vercel --prod
```

Bu sengа URL beradi, masalan `https://kiberqalqon-cloud.vercel.app`.

### 4) Telegram webhookni ulash (1 daqiqa)

```powershell
$env:TELEGRAM_BOT_TOKEN="123:AAH..."
$env:TELEGRAM_WEBHOOK_SECRET="random-32-hex"
$env:VERCEL_URL="https://kiberqalqon-cloud.vercel.app"
node scripts/set-webhook.mjs
```

Natija: `✅ Webhook o'rnatildi`.

Endi guruhga `/help` yoz — bot javob beradi. Tayyor.

---

## Tekshirish

```powershell
$env:VERCEL_URL="https://kiberqalqon-cloud.vercel.app"
$env:DEVICE_SHARED_SECRET="..."
node scripts/ping.mjs
```

Natija: `{ "ok": true, "stats": { ... } }`.

---

## API

| Endpoint | Method | Auth header | Vazifa |
|----------|--------|-------------|--------|
| `/api/scan/upload` | POST | `x-device-secret` | Android skan natijasini yuboradi |
| `/api/device/register` | POST | `x-device-secret` | Qurilmani ro'yxatdan o'tkazish |
| `/api/stats` | GET | `x-device-secret` | Bugungi statistika |
| `/api/telegram/webhook` | POST | `x-telegram-bot-api-secret-token` | Telegramdan keladi |

### Misol — Android'dan scan yuborish

```http
POST /api/scan/upload
Content-Type: application/json
x-device-secret: <DEVICE_SHARED_SECRET>

{
  "device_token": "abc123...",
  "apk_hash": "a3f5b9c2...64 hex chars",
  "package_name": "com.example.malware",
  "app_label": "Free VPN",
  "apk_size": 5242880,
  "verdict": "danger",
  "reasons": ["READ_SMS", "BIND_ACCESSIBILITY_SERVICE"],
  "perms": ["android.permission.READ_SMS"]
}
```

---

## Bot buyruqlari

| Buyruq | Vazifa |
|--------|--------|
| `/help` | Yordam |
| `/stats` | Bugungi raqamlar |
| `/last [N]` | Oxirgi N ta skan (default 5) |
| `/threats` | Xavfli APKlar ro'yxati |
| `/devices` | Ulangan qurilmalar |
| `/id` | Sizning chat ID |

---

## Local development

```powershell
cd "C:\Users\Muhammadali\Desktop\APK Virus Analysis\ApkGuard\cloud"
cp .env.example .env.local   # va ichini to'ldir
npx vercel dev
```

`http://localhost:3000` da ishlaydi.

---

## Limitlar (free tier)

| Servis | Limit |
|--------|-------|
| Vercel Hobby | 100 GB-hours/oy, 10s function timeout |
| Supabase Free | 500 MB DB, 2 GB bandwidth, 50k MAU |
| Telegram Bot | cheksiz (rate limit: 30 msg/sec) |

Bitta telefon kuniga ~50 skan yuborsa, 10 yilgacha free tier yetadi.

---

## Keyingi qadam — Android tarafi

`ApkGuard/app/` ichida `ApkScanner.kt` skan tugagandan keyin natijani
`/api/scan/upload` ga POST qilishi kerak. Bu hali yozilmagan — alohida task.
