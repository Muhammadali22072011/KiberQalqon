# KiberQalqon Cloud — Vercel + Supabase + Telegram + Panel

Android ilovasi skan natijalarini yuboradigan, Supabase bazasida saqlaydigan,
Telegram orqali xabar beradigan va **markaziy web-panelda** (xarita + statistika)
ko'rsatadigan serverless backend.

```
📱 Android  ─(x-device-secret)─►  ☁️ Vercel API  ─►  🗄️ Supabase
                                       │  ▲
                                       │  └──(x-admin-secret)── 🖥️ Panel (xarita + KPI + oqim)
                                       ▼
                                  💬 Telegram guruh (xavfli APK alert)
```

**Ikki xil kalit (eng muhim tushuncha):**

| Kalit | Sarlavha | Kim ishlatadi | APK ichida? |
|-------|----------|---------------|-------------|
| `DEVICE_SHARED_SECRET` | `x-device-secret` | Android ilova — **yozish** (`/api/scan/upload`, `/api/device/register`) | ✅ ha (kompilatsiya qilinadi) |
| `ADMIN_SECRET` | `x-admin-secret` | Faqat **panel/admin** — **o'qish** (`/api/stats`, `/api/geo`, `/api/feed`, …) | ❌ **YO'Q, hech qachon** |

> Sabab: APK leak bo'lsa ham, undagi `DEVICE_SHARED_SECRET` bilan faqat *yangi*
> ma'lumot yuborib bo'ladi — **boshqalarning** ma'lumotini ko'rib bo'lmaydi.
> Barcha o'qish endpointlari `ADMIN_SECRET` talab qiladi, u esa hech qaerda
> tarqatilmaydi.

---

## Sozlash — 6 qadam

### 1) Supabase loyihasi (5 daqiqa)

1. <https://supabase.com> ga kirib **New project** bos
2. Region: `Frankfurt (eu-central-1)` (Toshkentga eng yaqini)
3. Database password — saqlab qo'y
4. Proyekt ochilganda → **SQL Editor → New query**
5. `supabase/schema.sql` ni to'liq ko'chir va **Run** (jadvallar + bot view'lari)
6. **Yana New query** → `supabase/02_geo.sql` ni ko'chir va **Run**
   (geo + risk ustunlari, xarita uchun `v_map_points`, oqim uchun `v_recent_threats`).
7. **Yana New query** → `supabase/03_roles.sql` ni ko'chir va **Run**
   (rollar tizimi: `roles`, `operators`, `role_login_audit` + `v_operators_safe`).
   Bu bo'lmasa panelda rol/operator boshqaruvi va `/api/role/*` 500 qaytaradi.
8. **Yana New query** → `supabase/04_news.sql` ni ko'chir va **Run**
   (`news` jadvali — paneldagi "Yangiliklar" lentasi). Bu bo'lmasa `/api/news` 500 qaytaradi.
   Barcha fayllar idempotent — qayta ishga tushirsa xato bermaydi.
9. **Settings → API** sahifasidan ikkita kalitni nusxala:
   - `Project URL` → `SUPABASE_URL`
   - `service_role` (secret!) → `SUPABASE_SERVICE_KEY`

### 2) Telegram bot (2 daqiqa)

1. Telegram'da [@BotFather](https://t.me/BotFather) ga `/newbot` yoz
2. Nomi: `KiberQalqon Alerts`, username: `kiberqalqon_xxx_bot`
3. BotFather token beradi → `TELEGRAM_BOT_TOKEN`
4. Yangi *guruh* yarat, botni admin qil
5. Guruhga [@userinfobot](https://t.me/userinfobot) qo'sh, u **chat_id** ni ko'rsatadi (manfiy son: `-1001234567890`) → `ADMIN_CHAT_IDS`

**Random kalitlar** (`TELEGRAM_WEBHOOK_SECRET`, `DEVICE_SHARED_SECRET`,
`ADMIN_SECRET`) — har biri uchun **alohida** 32+ belgi yarat:

```powershell
# Windows PowerShell — uch marta ishga tushir, har birини alohida ENV uchun:
[guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
```
```bash
# Linux/Mac:
openssl rand -hex 32
```

### 3) Vercel deploy (3 daqiqa)

```powershell
cd "C:\Users\Muhammadali\Desktop\APK Virus Analysis\ApkGuard\cloud"
npm install
npx vercel login
npx vercel link        # yangi loyiha yaratasan
```

Keyin **Vercel Dashboard → Project → Settings → Environment Variables** ga
quyidagilarni qo'sh (`.env.example` dagi *barcha* qiymatlar):

| Variable | Qayerdan |
|----------|----------|
| `SUPABASE_URL` | Supabase Settings → API |
| `SUPABASE_SERVICE_KEY` | Supabase Settings → API (`service_role`) |
| `TELEGRAM_BOT_TOKEN` | @BotFather |
| `TELEGRAM_WEBHOOK_SECRET` | random 32 hex |
| `DEVICE_SHARED_SECRET` | random 32 hex — **APK'ga ham shu qiymat kerak** (6-qadam) |
| `ADMIN_SECRET` | random 32 hex — **faqat panel uchun, APK'ga QO'YILMAYDI** |
| `ADMIN_CHAT_IDS` | guruh chat_id (manfiy son) |
| `ADMIN_TOTP_SECRET` | (ixtiyoriy) panel 2FA — autentifikator base32 sekret. Bo'sh = faqat parol |
| `SESSION_SECRET` | (ixtiyoriy) panel sessiya tokenini imzolash; bo'sh = ADMIN_SECRET |
| `ROLE_CODE_SECRET` | rollar tizimi — soatlik umumiy kod shu sekretdan hisoblanadi |

Endi deploy:

```powershell
npx vercel --prod
```

Bu senga URL beradi, masalan `https://kiberqalqon-cloud.vercel.app`.

### 4) Telegram webhookni ulash (1 daqiqa)

```powershell
$env:TELEGRAM_BOT_TOKEN="123:AAH..."
$env:TELEGRAM_WEBHOOK_SECRET="random-32-hex"
$env:VERCEL_URL="https://kiberqalqon-cloud.vercel.app"
node scripts/set-webhook.mjs
```

Natija: `✅ Webhook o'rnatildi`. Guruhga `/help` yoz — bot javob beradi.

### 5) Panelni ochish (markaziy monitoring)

1. Brauzerda deploy URL'ini och: `https://kiberqalqon-cloud.vercel.app/`
2. Kirish: `ADMIN_SECRET` (parol) + (agar 2FA yoqilgan bo'lsa) autentifikator
   ilovasidagi 6 xonali kod. Server tekshiradi va **qisqa muddatli sessiya tokeni**
   beradi — master kalit brauzerda SAQLANMAYDI (faqat ~8 soatlik token).
3. Ko'rasan: O'zbekiston xaritasi (har nuqta = bitta telefon, rang yashil→qizil
   risk bo'yicha — nuqtaga bossang qurilma kartochkasi ochiladi), KPI kartalar,
   jonli tahdid oqimi, top zararli oilalar, hududlar jadvali.

**Panelni 2FA bilan himoyalash (qiyin kirish — tavsiya etiladi):**

1. base32 sekret yarat (yoki istalgan TOTP generatordan ol):
   ```powershell
   node -e "const c=require('crypto');const A='ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';let b=c.randomBytes(20),s='',v=0,n=0;for(const x of b){v=(v<<8)|x;n+=8;while(n>=5){n-=5;s+=A[(v>>>n)&31]}}console.log(s)"
   ```
2. Shu sekretni Google Authenticator / Authy'ga **"qo'lda kalit kiritish"** orqali qo'sh
   (hisob nomi: KiberQalqon, kalit: yuqoridagi satr).
3. Shu sekretni Vercel env'ga `ADMIN_TOTP_SECRET` deb qo'sh va qayta deploy qil.
4. Endi panelga kirishda parol + ilovadagi 6 xonali kod kerak bo'ladi.

> `ADMIN_TOTP_SECRET` bo'sh bo'lsa 2FA o'chiq (faqat parol) — o'zingni qulflab
> qo'ymaslik uchun. Sekretni qo'shganingdan keyingina 2FA majburiy bo'ladi.

Sahifa himoyasi (avtomatik, `vercel.json`): `X-Frame-Options: DENY` (clickjacking
yo'q), `Content-Security-Policy`, `Strict-Transport-Security` (HSTS), `nosniff`,
`Referrer-Policy: no-referrer` — barcha javoblarga qo'shiladi.

> Hozircha qurilmalar yo'q bo'lsa xarita bo'sh — bu normal. 6-qadamdan keyin
> ilova ma'lumot yubora boshlaydi va nuqtalar paydo bo'ladi.

### 6) Android ilovani cloudga ulash

`ApkGuard/local.properties` (gitignore'da) ga qo'sh — namuna
`local.properties.example` da:

```properties
cloud.base.url=https://kiberqalqon-cloud.vercel.app
cloud.device.secret=<Vercel'dagi DEVICE_SHARED_SECRET bilan BIR XIL>
```

> `cloud.base.url` — oxiriga `/` qo'yma, faqat `https://` (HTTP rad etiladi).
> `cloud.device.secret` — bu **DEVICE_SHARED_SECRET**, ADMIN emas!

Keyin APK'ni qayta qur:

```powershell
cd "C:\Users\Muhammadali\Desktop\APK Virus Analysis\ApkGuard"
.\gradlew.bat assembleDebug
```

**Foydalanuvchi tomonda:** ma'lumot yuborilishi uchun ConsentActivity'da
**3-galochka ("Jamoatchilik xavfsizligi")** yoqilgan bo'lishi shart (opt-in).
Galochka yoqilmasa yoki `cloud.*` bo'sh bo'lsa — ilova hech narsa yubormaydi
(maxfiylik: shaxsiy build/forklar uchun butunlay no-op).

---

## Tekshirish

```powershell
$env:VERCEL_URL="https://kiberqalqon-cloud.vercel.app"
$env:ADMIN_SECRET="..."
node scripts/ping.mjs
```

Natija: `{ "ok": true, "stats": { ... } }`.

---

## API

**Yozish (Android, `x-device-secret`):**

| Endpoint | Method | Vazifa |
|----------|--------|--------|
| `/api/scan/upload` | POST | Skan natijasini yuboradi (har skan, SAFE ham) |
| `/api/device/register` | POST | Qurilmani ro'yxatdan o'tkazadi (xaritada nuqta) |
| `/api/role/login` | POST | Maxfiy kirish: `{code, login, password}` → rol (huquqlar) |

**Kirish (panel sessiyasi — sarlavhasiz, qiymatlar body'da):**

| Endpoint | Method | Vazifa |
|----------|--------|--------|
| `/api/admin/login` | POST | `{secret, otp}` → qisqa muddatli sessiya tokeni (2FA) |

**O'qish (Panel/admin, `x-admin-secret` yoki sessiya tokeni):**

| Endpoint | Method | Vazifa |
|----------|--------|--------|
| `/api/stats` | GET | Umumiy + bugungi statistika |
| `/api/geo` | GET | Xarita nuqtalari (`v_map_points`) |
| `/api/feed` | GET | Jonli tahdid oqimi (oxirgi xavfli/shubhali) |
| `/api/devices` | GET | Qurilmalar ro'yxati (geo + risk bilan) |
| `/api/device/:id` | GET | Bitta qurilma + oxirgi 20 skani (uuid bo'yicha) |
| `/api/threats` | GET | Zararli APK oilalari ro'yxati |
| `/api/scans` | GET | Skan tarixi (`?verdict=danger&limit=50`) |
| `/api/role/code` | GET | Joriy soatlik kod (rollar tizimi uchun) |
| `/api/news` | GET / POST | Yangiliklar lentasi (o'qish + e'lon qo'shish/o'chirish/qotirish) |

**Telegram (`x-telegram-bot-api-secret-token`):**

| Endpoint | Method | Vazifa |
|----------|--------|--------|
| `/api/telegram/webhook` | POST | Telegramdan keladi (bot buyruqlari) |

### Misol — Android'dan scan yuborish

```http
POST /api/scan/upload
Content-Type: application/json
x-device-secret: <DEVICE_SHARED_SECRET>

{
  "device_token": "abc123...(>=16 belgi)",
  "apk_hash": "a3f5b9c2...64 hex chars",
  "package_name": "com.example.malware",
  "app_label": "Free VPN",
  "apk_size": 5242880,
  "verdict": "danger",
  "risk_score": 86,
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

## Maxfiylik (nima yuboriladi, nima YO'Q)

**Yuboriladi:** anonim `device_token` (tasodifiy UUID), qurilma modeli,
Android/ilova versiyasi, skan natijasi (apk_hash, paket nomi, yorliq, verdict,
risk ball, sabablar, xavfli ruxsatlar). Geo — server tomonda Vercel IP
sarlavhalaridan **shahar darajasida** (taxminiy), GPS emas.

**Hech qachon yuborilmaydi:** APK faylning o'zi, IMEI/seriya/MAC/IP/GPS,
foydalanuvchi ismi/telefoni, boshqa o'rnatilgan ilovalar ro'yxati.

---

## Local development

```powershell
cd "C:\Users\Muhammadali\Desktop\APK Virus Analysis\ApkGuard\cloud"
cp .env.example .env.local   # va ichini to'ldir
npx vercel dev
```

`http://localhost:3000` da ishlaydi. (Lokalda Vercel IP sarlavhalari bo'lmaydi —
geo `null` qoladi, bu normal; deploy'da real ishlaydi.)

---

## Limitlar (free tier)

| Servis | Limit |
|--------|-------|
| Vercel Hobby | 100 GB-hours/oy, 30s function timeout |
| Supabase Free | 500 MB DB, 2 GB bandwidth, 50k MAU |
| Telegram Bot | cheksiz (rate limit: 30 msg/sec) |

Bitta telefon kuniga ~50 skan yuborsa, 10 yilgacha free tier yetadi.
